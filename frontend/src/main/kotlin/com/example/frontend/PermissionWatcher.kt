package com.example.frontend

import com.example.model.SessionDto
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.example.frontend.toolWindow.VisualiserToolWindowFactory
import com.example.CoroutineScopeHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows one balloon per permission request when it first becomes pending, even if the tool window is closed. */
internal class PermissionWatcher : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Collect in a project-lifetime scope so this startup activity returns immediately.
        CoroutineScopeHolder.getInstance(project).createScope(PermissionWatcher::class.java.simpleName).launch { watch(project) }
    }

    private suspend fun watch(project: Project) {
        // Keyed per session: toolCallIds can repeat across agents (call_1) or be blank.
        var pendingBefore = emptySet<String>()
        VisualiserFrontendService.getInstance(project).sessions.collect { sessions ->
            val pending = sessions.mapNotNull { s -> s.pendingPermission?.let { p -> "${s.sessionId}\u0000${p.toolCallId}\u0000${p.title}" to (s to p) } }
            val fresh = pending.filter { (key, _) -> key !in pendingBefore }.map { it.second }
            pendingBefore = pending.mapTo(HashSet()) { it.first }
            if (fresh.isNotEmpty()) withContext(Dispatchers.EDT) { fresh.forEach { (s, p) -> notify(project, s, p.title) } }
        }
    }

    private fun notify(project: Project, session: SessionDto, title: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(Notifier.PERMISSIONS_GROUP)
            .createNotification(
                FrontendBundle.message("permission.title", session.agentName),
                FrontendBundle.message("permission.content", session.agentName, title.ifBlank { "a tool call" }),
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring(FrontendBundle.message("action.show.map")) {
                ToolWindowManager.getInstance(project).getToolWindow(VisualiserToolWindowFactory.ID)?.activate(null)
            })
            .notify(project)
    }
}
