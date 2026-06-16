package dev.flaticols.deliverypipeline

import com.intellij.execution.services.ServiceEventListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.flaticols.deliverypipeline.gcloud.CloudDeployApi
import dev.flaticols.deliverypipeline.gcloud.CloudDeployException
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.Snapshot
import dev.flaticols.deliverypipeline.model.TargetState
import dev.flaticols.deliverypipeline.services.PipelinesServiceContributor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached per-pipeline snapshots + async refresh on the platform-injected
 * coroutine scope. Fetches run on [Dispatchers.IO] (never the EDT); every
 * landed result resets the Services view (on the EDT) so the tree re-reads the
 * snapshots. Also hosts [background] — the project's one entry point for "run
 * off the EDT, deliver the result on the EDT".
 */
@Service(Service.Level.PROJECT)
class PipelinesModel(private val project: Project, private val cs: CoroutineScope) {

    private val snapshots = ConcurrentHashMap<PipelineRef, Snapshot>()
    private val inFlight = ConcurrentHashMap.newKeySet<PipelineRef>()

    fun snapshot(ref: PipelineRef): Snapshot = snapshots[ref] ?: Snapshot.Loading

    /** First-time lazy load, triggered when the tree renders an unknown pipeline. */
    fun ensureLoaded(ref: PipelineRef) {
        if (!snapshots.containsKey(ref)) refresh(ref)
    }

    fun refresh(ref: PipelineRef) {
        if (!inFlight.add(ref)) return
        cs.launch {
            val result = try {
                // Shows a "Refreshing <pipeline>…" indicator in the IDE progress area
                // while the (manual) refresh runs.
                withBackgroundProgress(project, "Refreshing ${ref.pipeline}…") {
                    withContext(Dispatchers.IO) { fetch(ref) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudDeployException) {
                Snapshot.Error(e.message ?: "Cloud Deploy request failed")
            } catch (e: Exception) {
                Snapshot.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                inFlight.remove(ref)
            }
            // Rebuild the view only when the data actually changed — a no-op refresh
            // leaves the open detail panel (and its scroll) untouched.
            if (snapshots.put(ref, result) != result) resetView()
        }
    }

    fun refreshAll() {
        PipelinesStore.getInstance(project).refs().forEach(::refresh)
    }

    /** Forget a removed pipeline's data. */
    fun drop(ref: PipelineRef) {
        snapshots.remove(ref)
        structureChanged()
    }

    /**
     * Run [io] off the EDT on the project scope, then deliver its [Result] to
     * [onResult] on the EDT. Pass [modality] = [ModalityState.any] so a modal
     * dialog can update its own UI while it is open. [onComplete] runs in a
     * `finally` — even if the project scope is cancelled mid-flight (project
     * close) — so callers can release dedup guards without leaking them.
     * Replaces the old AppExecutorUtil + invokeLater pairs.
     */
    fun <T> background(
        io: () -> T,
        modality: ModalityState = ModalityState.nonModal(),
        onComplete: () -> Unit = {},
        onResult: (Result<T>) -> Unit,
    ) {
        cs.launch {
            try {
                val result = try {
                    Result.success(withContext(Dispatchers.IO) { io() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                withContext(Dispatchers.EDT + modality.asContextElement()) { onResult(result) }
            } finally {
                onComplete()
            }
        }
    }

    /** Asks the Services view to rebuild our contributor's subtree (from any thread). */
    fun structureChanged() {
        cs.launch { resetView() }
    }

    private suspend fun resetView() {
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            project.messageBus.syncPublisher(ServiceEventListener.TOPIC)
                .handle(ServiceEventListener.ServiceEvent.createResetEvent(PipelinesServiceContributor::class.java))
        }
    }

    private fun fetch(ref: PipelineRef): Snapshot.Data {
        val stages = CloudDeployApi.pipelineStages(ref)
        val approvals = CloudDeployApi.targetApprovals(ref.gcpProject, ref.region)
        // Best-effort IAM probe — a failure must not sink the whole snapshot.
        val canApprove = runCatching { CloudDeployApi.canApproveRollouts(ref) }.getOrNull()
        // Recent releases for the Releases node — also best-effort.
        val releases = runCatching { CloudDeployApi.recentReleases(ref) }.getOrDefault(emptyList())
        // Newest rollout per target wins — targets may run different releases.
        val byTarget = CloudDeployApi.recentRollouts(ref, stages.toSet())
            .groupBy { it.targetId }
            .mapValues { (_, rollouts) -> rollouts.sortedByDescending { it.createTime } }
        return Snapshot.Data(
            stages.map { targetId ->
                val history = byTarget[targetId].orEmpty()
                TargetState(
                    targetId = targetId,
                    requireApproval = approvals[targetId] ?: false,
                    latest = history.firstOrNull(),
                    // The serving version: newest rollout that actually succeeded.
                    current = history.firstOrNull { it.state == "SUCCEEDED" },
                    history = history.take(HISTORY_DEPTH),
                )
            },
            canApprove = canApprove,
            releases = releases,
        )
    }

    companion object {
        private const val HISTORY_DEPTH = 10

        fun getInstance(project: Project): PipelinesModel = project.service()
    }
}
