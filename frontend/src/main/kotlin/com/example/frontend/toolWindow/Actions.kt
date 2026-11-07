package com.example.frontend.toolWindow

import com.example.CoroutineScopeHolder
import com.example.frontend.FrontendBundle
import com.example.frontend.Notifier
import com.example.frontend.VisualiserFrontendService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.swing.Icon

/** Base for toolbar actions that make one backend call and report the result as a balloon. */
internal abstract class BackendAction(textKey: String, descriptionKey: String, icon: Icon) :
    DumbAwareAction(FrontendBundle.message(textKey), FrontendBundle.message(descriptionKey), icon) {

    private val label = FrontendBundle.message(textKey)

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = VisualiserFrontendService.getInstance(project)
        CoroutineScopeHolder.getInstance(project).getPluginScope().launch {
            try {
                run(project, service)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Notifier.error(project, label, ex)
            }
        }
    }

    protected abstract suspend fun run(project: Project, service: VisualiserFrontendService)

    protected fun esc(s: String): String = StringUtil.escapeXmlEntities(s).replace("\n", "<br>")
}

internal class WrapAgentsAction : BackendAction("action.wrap.text", "action.wrap.description", AllIcons.General.Add) {
    override suspend fun run(project: Project, service: VisualiserFrontendService) {
        val r = service.wrapAgents()
        val changed = r.wrapped.isNotEmpty()
        val body = buildString {
            append(esc(r.message))
            if (changed) append("<br><b>").append(FrontendBundle.message("wrap.restart")).append("</b>")
        }
        Notifier.info(project, body)
    }
}

internal class RebuildMapAction : BackendAction("action.rebuild.text", "action.rebuild.description", AllIcons.Actions.Refresh) {
    override suspend fun run(project: Project, service: VisualiserFrontendService) {
        service.rebuildWorld()
    }
}

internal class ReplayLastSessionAction : BackendAction("action.replay.text", "action.replay.description", AllIcons.Actions.Rerun) {
    override suspend fun run(project: Project, service: VisualiserFrontendService) {
        Notifier.info(project, esc(service.startReplay(null)))
    }
}

internal class TapStatusAction : BackendAction("action.status.text", "action.status.description", AllIcons.General.Information) {
    override suspend fun run(project: Project, service: VisualiserFrontendService) {
        val s = service.status()
        Notifier.info(project, FrontendBundle.message("status.message", s.port.toString(), s.connectedTaps.toString(), esc(s.registryFile), esc(s.logsDir)))
    }
}
