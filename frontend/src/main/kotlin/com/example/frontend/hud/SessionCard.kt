package com.example.frontend.hud

import com.example.frontend.render.AgentColors
import com.example.frontend.render.Sprites
import com.example.model.ConfigOptionDto
import com.example.model.ConfigValueDto
import com.example.model.SessionDto
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.ImageIcon
import javax.swing.JPanel
import javax.swing.JProgressBar

/** HUD card for one agent session. Updated in place so open combo popups survive refreshes. */
internal class SessionCard(
    private val sprites: Sprites,
    private val slot: Int,
    private val onConfigChange: (sessionId: String, configId: String, value: String) -> Unit,
) : JPanel() {
    private val title = JBLabel()
    private val status = JBLabel()
    private val configRow = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {
        // Don't let the vertical BoxLayout stretch this row.
        override fun getMaximumSize() = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private val modelLine = JBLabel()
    private val activity = JBLabel()
    private val permission = JBLabel()
    private val tokens = JBLabel()
    private val contextLabel = JBLabel()
    private val contextBar = JProgressBar(0, 1000)
    private val cost = JBLabel()
    private val quest = JBLabel()
    private val combos = LinkedHashMap<String, ComboBox<ConfigValueDto>>()
    private var lastOptions: List<ConfigOptionDto>? = null
    private var updating = false
    private var sessionId = ""

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        val color = AgentColors.color(slot)
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(color, 0, 3, 0, 0),
            JBUI.Borders.empty(6, 8),
        )
        title.icon = headIcon()
        title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 1f)
        status.foreground = UIUtil.getContextHelpForeground()
        permission.foreground = JBColor.RED
        permission.font = permission.font.deriveFont(Font.BOLD)
        configRow.isOpaque = false
        contextBar.isStringPainted = true
        contextBar.alignmentX = Component.LEFT_ALIGNMENT
        contextBar.maximumSize = java.awt.Dimension(Int.MAX_VALUE, contextBar.preferredSize.height)

        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(status, BorderLayout.EAST)
        }
        for (c in listOf(header, configRow, modelLine, activity, permission, tokens, contextLabel, contextBar, cost, quest)) {
            (c as? javax.swing.JComponent)?.alignmentX = Component.LEFT_ALIGNMENT
            add(c)
        }
        header.maximumSize = java.awt.Dimension(Int.MAX_VALUE, header.preferredSize.height)
    }

    fun update(s: SessionDto) {
        sessionId = s.sessionId
        val replay = if (s.replay) " (replay)" else ""
        title.text = s.agentName.ifBlank { s.sessionId.take(8) } + replay
        status.text = when {
            s.pendingPermission != null -> "waiting"
            s.turnActive -> "working"
            else -> "idle"
        }

        updateConfig(s)
        val parts = listOfNotNull(s.model?.let { "Model: $it" }, s.effort?.let { "Effort: $it" }, s.mode?.let { "Mode: $it" })
        modelLine.text = parts.joinToString("   ")
        modelLine.isVisible = combos.isEmpty() && parts.isNotEmpty()

        val act = s.activity
        val what = act.kind.name.lowercase().replace('_', ' ')
        val detail = act.title.ifBlank { act.path ?: "" }
        activity.text = "<html>${esc(what)}${if (detail.isNotBlank()) ": " + esc(StringUtil.shortenTextWithEllipsis(detail, 60, 20)) else ""}" +
            (if (act.failed) " <font color='#dc4a3f'>(failed)</font>" else "") + "</html>"
        activity.toolTipText = listOfNotNull(act.title.ifBlank { null }, act.path).joinToString("\n").ifBlank { null }

        permission.isVisible = s.pendingPermission != null
        permission.text = s.pendingPermission?.let { "Needs permission: " + StringUtil.shortenTextWithEllipsis(it.title, 50, 0) } ?: ""

        val u = s.usage
        val est = if (u.tokensEstimated) " (est.)" else ""
        tokens.text = "Tokens: ${Format.tokens(u.inputTokens)} in / ${Format.tokens(u.outputTokens)} out$est"

        val used = u.contextUsed
        val size = u.contextSize
        if (used != null && size != null && size > 0) {
            contextLabel.text = "Context"
            contextBar.isVisible = true
            contextBar.value = ((used.toDouble() / size) * 1000).toInt().coerceIn(0, 1000)
            contextBar.string = "${Format.tokens(used)} / ${Format.tokens(size)} (${(used * 100 / size)}%)"
        } else {
            contextLabel.text = "Context: " + (used?.let { Format.tokens(it) + " used" } ?: "not reported")
            contextBar.isVisible = false
        }

        val so = Format.money(u.costSoFar, u.currency) + if (u.costEstimated) " estimate" else ""
        val low = u.projectedCostLow
        val high = u.projectedCostHigh
        val proj = if (low != null && high != null)
            " &middot; projected ${Format.money(low, u.currency)}&ndash;${Format.money(high, u.currency)} (estimate)"
        else ""
        cost.text = "<html>Cost: <b>${esc(so)}</b>$proj</html>"
        cost.toolTipText = if (u.alternativeCosts.isEmpty()) null else
            "<html><b>Same tokens on other models</b><br>" +
                u.alternativeCosts.entries.sortedBy { it.value }.joinToString("<br>") { (m, c) -> "${esc(m)}: ${Format.money(c, u.currency)}" } +
                "</html>"

        quest.isVisible = s.plan.isNotEmpty()
        quest.text = if (s.plan.isEmpty()) "" else "<html><b>Quest log</b><br>" + s.plan.joinToString("<br>") { e ->
            val mark = when (e.status) {
                "completed" -> "<font color='#4fa04c'>&#10004;</font>"
                "in_progress" -> "<font color='#f2c53d'>&#9656;</font>"
                else -> "&#9675;"
            }
            val text = esc(StringUtil.shortenTextWithEllipsis(e.content, 70, 0))
            "$mark " + if (e.status == "completed") "<font color='gray'>$text</font>" else text
        } + "</html>"
    }

    private fun updateConfig(s: SessionDto) {
        val options = s.configOptions.filter { it.values.size > 1 }
        val shape = options.map { it.id to it.values }
        if (lastOptions?.map { it.id to it.values } != shape) {
            configRow.removeAll()
            combos.clear()
            for (o in options) {
                val combo = ComboBox(DefaultComboBoxModel(o.values.toTypedArray()))
                combo.renderer = object : SimpleListCellRenderer<ConfigValueDto>() {
                    override fun customize(list: javax.swing.JList<out ConfigValueDto>, value: ConfigValueDto?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        text = value?.let { it.name.ifBlank { it.value } } ?: ""
                    }
                }
                combo.toolTipText = o.name
                combo.addActionListener {
                    if (updating) return@addActionListener
                    val v = combo.selectedItem as? ConfigValueDto ?: return@addActionListener
                    onConfigChange(sessionId, o.id, v.value)
                }
                configRow.add(JBLabel(o.name.ifBlank { o.category ?: o.id } + ":"))
                configRow.add(combo)
                combos[o.id] = combo
            }
            configRow.revalidate()
        }
        lastOptions = options
        configRow.isVisible = combos.isNotEmpty()
        updating = true
        try {
            for (o in options) {
                val combo = combos[o.id] ?: continue
                if (combo.isPopupVisible) continue
                o.values.firstOrNull { it.value == o.currentValue }?.let { if (combo.selectedItem != it) combo.selectedItem = it }
            }
        } finally {
            updating = false
        }
    }

    private fun headIcon(): ImageIcon {
        val src = sprites.tinted("hero_down_0", AgentColors.tint(slot))
        val size = JBUI.scale(2) * 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(src, 0, 0, size, size, null)
        g.dispose()
        return ImageIcon(img)
    }

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)
}
