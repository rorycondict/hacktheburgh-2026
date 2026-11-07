package com.example.frontend.hud

import com.example.frontend.FrontendBundle
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/** "Ask about this file/room" box. [onAsk] runs the question and calls back with the answer (on the EDT). */
internal class AskPanel(
    private val onAsk: (path: String, question: String, done: (String) -> Unit) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(4))) {
    private val target = JBLabel(FrontendBundle.message("hud.ask.none"))
    private val field = JBTextField()
    private val button = JButton(FrontendBundle.message("hud.ask.button"))
    private val answer = JBTextArea(5, 20).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    /** Hidden until the first question, so the input row stays near the top and always visible. */
    private val answerScroll = JBScrollPane(answer).apply { isVisible = false }
    private var path: String? = null

    init {
        border = JBUI.Borders.empty(6, 8)
        target.foreground = UIUtil.getContextHelpForeground()
        field.emptyText.text = FrontendBundle.message("hud.ask.placeholder")
        val row = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(field, BorderLayout.CENTER)
            add(button, BorderLayout.EAST)
        }
        add(target, BorderLayout.NORTH)
        add(row, BorderLayout.CENTER)
        add(answerScroll, BorderLayout.SOUTH)
        button.addActionListener { submit() }
        field.addActionListener { submit() }
        setTarget(null, null)
    }

    /** [path] is project-relative ("" = project root / lobby). */
    fun setTarget(path: String?, label: String?) {
        this.path = path
        target.text = if (path == null) FrontendBundle.message("hud.ask.none") else "Ask about: ${label ?: path}"
        button.isEnabled = path != null
        field.isEnabled = path != null
    }

    private fun submit() {
        val p = path ?: return
        val q = field.text.trim()
        if (q.isEmpty()) return
        button.isEnabled = false
        answer.text = "…"
        if (!answerScroll.isVisible) {
            answerScroll.isVisible = true
            revalidate()
        }
        onAsk(p, q) { result ->
            answer.text = result
            answer.caretPosition = 0
            button.isEnabled = path != null
        }
    }
}
