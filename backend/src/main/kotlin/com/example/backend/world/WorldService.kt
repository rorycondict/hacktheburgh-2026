package com.example.backend.world

import com.example.backend.session.PathUtil
import com.example.model.WorldDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Builds the project's [WorldDto] and keeps it fresh (explicit rebuilds + debounced VFS changes). */
@Service(Service.Level.PROJECT)
class WorldService(private val project: Project, private val cs: CoroutineScope) {
    private val _world = MutableStateFlow<WorldDto?>(null)
    val world: StateFlow<WorldDto?> = _world.asStateFlow()

    private val started = AtomicBoolean(false)
    private val lock = Any()
    private var buildJob: Job? = null
    private var pendingJob: Job? = null
    private var pendingAt = 0L
    @Volatile var lastIndex: IndexResult? = null
        private set

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        subscribeVfs()
        rebuild()
    }

    fun rebuild(): Job = synchronized(lock) {
        started.set(true)
        buildJob?.cancel()
        cs.launch {
            try {
                val idx = ProjectIndexer.index(project)
                lastIndex = idx
                publish(idx)
                project.service<SummaryService>().ensure(idx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("World build failed", e)
            }
        }.also { buildJob = it }
    }

    /** Re-lays out the last index with the latest summaries (no re-index). */
    fun summariesChanged() {
        lastIndex?.let(::publish)
    }

    /** Re-lays out the last index after the agent changed the floorplan (no re-index). */
    fun planChanged() {
        lastIndex?.let(::publish)
    }

    private fun publish(idx: IndexResult) {
        _world.value = layout(idx)
    }

    /** The agent's plan if there is one, else the automatic, role-based layout. */
    fun layout(idx: IndexResult): WorldDto {
        val summaries = project.service<SummaryService>().current()
        val plan = project.service<FloorplanPlanService>().plan
        return if (plan != null && plan.rooms.isNotEmpty()) {
            WorldLayout.build(plan.rooms, idx.files, idx.revision, summaries, System.currentTimeMillis(), idx.deps, curated = true, libraries = idx.libraries)
        } else {
            WorldLayout.build(idx.files, idx.revision, summaries, deps = idx.deps, libraries = idx.libraries)
        }
    }

    /** Waits for the current build (if any) and returns the latest index, indexing now if there is none. */
    suspend fun index(): IndexResult {
        buildJob?.join()
        lastIndex?.let { return it }
        return ProjectIndexer.index(project).also { lastIndex = it; publish(it) }
    }

    private fun scheduleRebuild(delayMs: Long): Unit = synchronized(lock) {
        val at = System.currentTimeMillis() + delayMs
        if (pendingJob?.isActive == true && pendingAt <= at) return
        pendingJob?.cancel()
        pendingAt = at
        pendingJob = cs.launch {
            delay(delayMs)
            rebuild()
        }
    }

    private fun subscribeVfs() {
        val base = project.basePath ?: return
        project.messageBus.connect(cs).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                var structural = false
                var content = false
                for (e in events) {
                    val path = e.path
                    if (!PathUtil.isUnder(path, base) || isSkipped(PathUtil.relativise(path, listOf(base)))) continue
                    when {
                        e is VFileContentChangeEvent -> content = true
                        e is VFilePropertyChangeEvent && e.propertyName != "name" -> {}
                        else -> structural = true
                    }
                }
                if (structural) scheduleRebuild(2_000) else if (content) scheduleRebuild(20_000)
            }
        })
    }

    private fun isSkipped(rel: String): Boolean =
        rel.split('/').dropLast(1).any { it in ProjectIndexer.SKIP_DIRS || it.startsWith(".") }

    companion object {
        private val LOG = logger<WorldService>()
        fun getInstance(project: Project): WorldService = project.service()
    }
}
