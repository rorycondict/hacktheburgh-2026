package com.example.frontend.toolWindow

import com.example.frontend.FrontendBundle
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class VisualiserToolWindowFactory : ToolWindowFactory, DumbAware {
    companion object {
        /** Must match the `id` in htb.frontend.xml. */
        const val ID = "AgentVisualiser"
    }

    override fun shouldBeAvailable(project: Project) = true

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = FrontendBundle.message("product.name")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = VisualiserPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
