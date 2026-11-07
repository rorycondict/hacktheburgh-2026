package com.example.backend.user

import com.example.backend.session.PathUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Tracks the file selected in the user's editor, so the map can show the user as a character too. */
@Service(Service.Level.PROJECT)
class UserPositionService(private val project: Project, private val cs: CoroutineScope) {
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        project.messageBus.connect(cs).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                _path.value = toPath(event.newFile)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (source.selectedFiles.isEmpty()) _path.value = null
            }
        })
        cs.launch(Dispatchers.EDT) {
            _path.value = toPath(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
        }
    }

    private fun toPath(file: VirtualFile?): String? {
        if (file == null || !file.isInLocalFileSystem) return null
        val base = project.basePath ?: return PathUtil.normalise(file.path)
        return PathUtil.relativise(file.path, listOf(base))
    }

    companion object {
        fun getInstance(project: Project): UserPositionService = project.service()
    }
}
