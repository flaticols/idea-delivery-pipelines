package dev.flaticols.deliverypipeline

import com.intellij.execution.services.ServiceEventListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.flaticols.deliverypipeline.gcloud.CloudDeployApi
import dev.flaticols.deliverypipeline.gcloud.CloudDeployException
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.Snapshot
import dev.flaticols.deliverypipeline.model.TargetState
import dev.flaticols.deliverypipeline.services.PipelinesServiceContributor
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached per-pipeline snapshots + async refresh. Fetches run on the shared
 * pooled executor (network, never the EDT); every landed result fires a
 * Services-view reset so the tree re-reads the snapshots.
 */
@Service(Service.Level.PROJECT)
class PipelinesModel(private val project: Project) {

    private val snapshots = ConcurrentHashMap<PipelineRef, Snapshot>()
    private val inFlight = ConcurrentHashMap.newKeySet<PipelineRef>()

    fun snapshot(ref: PipelineRef): Snapshot = snapshots[ref] ?: Snapshot.Loading

    /** First-time lazy load, triggered when the tree renders an unknown pipeline. */
    fun ensureLoaded(ref: PipelineRef) {
        if (!snapshots.containsKey(ref)) refresh(ref)
    }

    fun refresh(ref: PipelineRef) {
        if (!inFlight.add(ref)) return
        AppExecutorUtil.getAppExecutorService().execute {
            val result = try {
                fetch(ref)
            } catch (e: CloudDeployException) {
                Snapshot.Error(e.message ?: "Cloud Deploy request failed")
            } catch (e: Exception) {
                Snapshot.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                inFlight.remove(ref)
            }
            snapshots[ref] = result
            structureChanged()
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

    /** Asks the Services view to rebuild our contributor's subtree. */
    fun structureChanged() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.messageBus.syncPublisher(ServiceEventListener.TOPIC)
                .handle(
                    ServiceEventListener.ServiceEvent.createResetEvent(
                        PipelinesServiceContributor::class.java,
                    ),
                )
        }
    }

    private fun fetch(ref: PipelineRef): Snapshot.Data {
        val stages = CloudDeployApi.pipelineStages(ref)
        val approvals = CloudDeployApi.targetApprovals(ref.gcpProject, ref.region)
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
        )
    }

    companion object {
        private const val HISTORY_DEPTH = 10

        fun getInstance(project: Project): PipelinesModel = project.service()
    }
}
