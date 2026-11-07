package com.example.backend

import com.example.backend.tap.TapServer
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the tap server as soon as a project opens, so taps launched by Air can connect. */
internal class FloorplanStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        TapServer.getInstance().ensureStarted()
    }
}
