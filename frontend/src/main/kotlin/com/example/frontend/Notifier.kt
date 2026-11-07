package com.example.frontend

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

internal object Notifier {
    const val PERMISSIONS_GROUP = "AgentVisualiser.Permissions"
    const val GENERAL_GROUP = "AgentVisualiser.General"

    fun info(project: Project, content: String, type: NotificationType = NotificationType.INFORMATION): Notification =
        NotificationGroupManager.getInstance().getNotificationGroup(GENERAL_GROUP)
            .createNotification(FrontendBundle.message("product.name"), content, type)
            .also { it.notify(project) }

    fun error(project: Project, what: String, t: Throwable): Notification =
        info(project, FrontendBundle.message("error.call.failed", what, t.message ?: t.javaClass.simpleName), NotificationType.ERROR)
}
