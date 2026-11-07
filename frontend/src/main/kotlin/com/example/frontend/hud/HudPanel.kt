package com.example.frontend.hud

import com.example.frontend.FrontendBundle
import com.example.frontend.render.Atlas
import com.example.model.SessionDto
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JSeparator

/** Per-session HUD (model, effort, tokens, context, cost, quest log, config pickers). Call [update] on the EDT. */
internal class HudPanel(
    private val atlas: Atlas,
    private val colorSlot: (String) -> Int,
    private val onConfigChange: (sessionId: String, configId: String, value: String) -> Unit,
) : JPanel() {
    private val cards = LinkedHashMap<String, SessionCard>()
    private val empty = JBLabel(FrontendBundle.message("hud.empty")).apply {
        border = JBUI.Borders.empty(12)
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private var order: List<String> = emptyList()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(empty)
    }

    fun update(sessions: List<SessionDto>) {
        val ids = sessions.map { it.sessionId }
        cards.keys.retainAll(ids.toSet())
        for (s in sessions) {
            cards.getOrPut(s.sessionId) { SessionCard(atlas, colorSlot(s.sessionId), onConfigChange) }.update(s)
        }
        if (ids != order) {
            order = ids
            removeAll()
            if (sessions.isEmpty()) add(empty)
            for ((i, id) in ids.withIndex()) {
                if (i > 0) add(JSeparator().apply { maximumSize = Dimension(Int.MAX_VALUE, 1); alignmentX = Component.LEFT_ALIGNMENT })
                add(cards.getValue(id).apply { alignmentX = Component.LEFT_ALIGNMENT })
            }
            add(Box.createVerticalGlue())
        }
        revalidate()
        repaint()
    }
}
