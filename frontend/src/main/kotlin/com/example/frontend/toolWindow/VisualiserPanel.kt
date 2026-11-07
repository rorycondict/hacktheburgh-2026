package com.example.frontend.toolWindow

import com.example.CoroutineScopeHolder
import com.example.frontend.FrontendBundle
import com.example.frontend.Notifier
import com.example.frontend.VisualiserFrontendService
import com.example.frontend.hud.AskPanel
import com.example.frontend.hud.HudPanel
import com.example.frontend.render.Sprites
import com.example.frontend.render.MapComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/** Tool window content: toolbar, map on the left, HUD (+ ask box) on the right. */
internal class VisualiserPanel(private val project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(false, true) {
    private val service = VisualiserFrontendService.getInstance(project)
    private val scope = CoroutineScopeHolder.getInstance(project).createScope(VisualiserPanel::class.java.simpleName)
    private val sprites = Sprites(VisualiserPanel::class.java)
    private val map = MapComponent(sprites, service::colorSlot)
    private val hud = HudPanel(sprites, service::colorSlot, ::changeConfig)
    private val ask = AskPanel(::ask)

    init {
        sprites["hero_down_0"]
        if ("hero_down_0" in sprites.missing) {
            thisLogger().warn("Sprites not found under /floorplan/sprites/: check that the frontend build copies assets/*.png")
        }
        Disposer.register(parentDisposable, map)
        Disposer.register(parentDisposable) { scope.cancel() }

        val right = JPanel(BorderLayout()).apply {
            add(JBScrollPane(hud).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = JBUI.Borders.empty()
            }, BorderLayout.CENTER)
            add(ask, BorderLayout.SOUTH)
        }
        val splitter = OnePixelSplitter(false, 0.68f).apply {
            firstComponent = map
            secondComponent = right
        }
        setContent(splitter)

        val group = DefaultActionGroup(WrapAgentsAction(), RebuildMapAction(), ReplayLastSessionAction(), TapStatusAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("AgentVisualiserToolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        map.emptyText = FrontendBundle.message("map.loading")
        map.onFurnitureClicked = { f ->
            map.selectedPath = f.path.ifEmpty { null }
            if (f.path.isNotEmpty()) {
                ask.setTarget(f.path, f.path)
                scope.launch {
                    try {
                        service.openFile(f.path, null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Notifier.error(project, "Open file", e)
                    }
                }
            }
        }
        map.onRoomClicked = { r ->
            map.selectedPath = r.path
            ask.setTarget(r.path, if (r.path.isEmpty()) r.name else "${r.name} (${r.path}/)")
        }

        scope.launch(Dispatchers.EDT) {
            service.world.collect { map.setWorld(it) }
        }
        scope.launch(Dispatchers.EDT) {
            service.sessions.collect { sessions ->
                map.setSessions(sessions)
                hud.update(sessions)
            }
        }
    }

    private fun changeConfig(sessionId: String, configId: String, value: String) {
        scope.launch {
            try {
                if (!service.setConfigOption(sessionId, configId, value)) {
                    Notifier.info(project, "Could not change $configId: the agent didn't accept it or isn't behind the tap.", NotificationType.WARNING)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Notifier.error(project, "Change $configId", e)
            }
        }
    }

    private fun ask(path: String, question: String, done: (String) -> Unit) {
        scope.launch {
            val answer = try {
                service.ask(path, question)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Ask failed: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.EDT) { done(answer) }
        }
    }
}
