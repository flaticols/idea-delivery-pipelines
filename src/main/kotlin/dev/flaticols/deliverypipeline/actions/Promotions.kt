package dev.flaticols.deliverypipeline.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.flaticols.deliverypipeline.PipelinesModel
import dev.flaticols.deliverypipeline.gcloud.CloudDeployApi
import dev.flaticols.deliverypipeline.gcloud.CloudDeployException
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.RolloutInfo
import dev.flaticols.deliverypipeline.model.Snapshot
import dev.flaticols.deliverypipeline.model.TargetState
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ConcurrentHashMap

/** Promote / approve / copy-link / copy-version operations shared by popup actions and detail-panel buttons. */
object Promotions {

    /**
     * Rollouts with an approve/reject decision currently in flight, keyed by
     * rollout name — dedups double-submits across the toolbar action, the
     * right-click popup, and the (possibly two) inline button panels, so a
     * second click cannot fire a second `:approve` and produce a spurious
     * "failed" notification once the first succeeds.
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Promotions currently dispatched, keyed by "<release>→<targetId>" — dedups double-submits. */
    private val inFlightPromotions = ConcurrentHashMap.newKeySet<String>()

    /** Rollouts with a retry-job request in flight, keyed by rollout name — dedups double-submits. */
    private val inFlightRetries = ConcurrentHashMap.newKeySet<String>()

    /**
     * The rollout that would be promoted into [targetId] — the previous serial
     * stage's latest. Null for the first stage (its rollouts come from new
     * releases, not promotion) or when the previous stage was never deployed.
     */
    fun promotionSource(data: Snapshot.Data, targetId: String): RolloutInfo? {
        val index = data.targets.indexOfFirst { it.targetId == targetId }
        if (index <= 0) return null
        return data.targets[index - 1].latest
    }

    /** Confirm, then promote the previous stage's release into [targetId]. */
    fun promoteToTarget(project: Project, ref: PipelineRef, targetId: String) {
        val data = PipelinesModel.getInstance(project).snapshot(ref) as? Snapshot.Data ?: return
        val source = promotionSource(data, targetId) ?: return notify(
            project, NotificationType.WARNING, "Nothing to promote",
            "No release on the stage before $targetId in ${ref.pipeline}.",
        )
        startPromotion(project, ref, source.release, targetId)
    }

    /**
     * Promote a specific [release] to a target the user picks from the
     * pipeline's stages. A release+target promote is exactly what the REST
     * `promote` does, so any release can be sent to any stage.
     */
    fun promoteRelease(project: Project, ref: PipelineRef, release: String) {
        val data = PipelinesModel.getInstance(project).snapshot(ref) as? Snapshot.Data ?: return
        val targets = data.targets.map { it.targetId }
        if (targets.isEmpty()) {
            notify(project, NotificationType.WARNING, "No targets",
                "${ref.pipeline} has no targets to promote to.")
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(targets)
            .setTitle("Promote $release to…")
            .setItemChosenCallback { targetId -> startPromotion(project, ref, release, targetId) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    /** Confirm + create the rollout that promotes [release] to [targetId], off the EDT, then refresh. */
    private fun startPromotion(project: Project, ref: PipelineRef, release: String, targetId: String) {
        val confirmed = MessageDialogBuilder.yesNo(
            "Promote Release",
            "Promote $release to $targetId in ${ref.pipeline}?",
        ).ask(project)
        if (!confirmed) return
        // Dedup a double-confirm of the same release→target before the first refresh lands.
        val key = "$release→$targetId"
        if (!inFlightPromotions.add(key)) return

        // Allocate the id and create the rollout off the EDT (nextRolloutId queries the API).
        val model = PipelinesModel.getInstance(project)
        model.background(
            {
                val rolloutId = nextRolloutId(ref, release, targetId)
                CloudDeployApi.promote(ref, release, targetId, rolloutId)
                rolloutId
            },
            onComplete = { inFlightPromotions.remove(key) }, // always released, even on project-close cancel
        ) { result ->
            result.onSuccess { rolloutId ->
                notify(project, NotificationType.INFORMATION, "Promotion started",
                    "Created rollout $rolloutId.")
                model.refresh(ref)
            }.onFailure {
                notify(project, NotificationType.ERROR, "Promotion failed",
                    it.message ?: "Cloud Deploy request failed.")
            }
        }
    }

    /**
     * Confirm, then approve [rollout] via the Cloud Deploy API and refresh.
     * Returns true once the request has been dispatched (user confirmed and no
     * other decision was already in flight); false otherwise — callers use this
     * to disable inline buttons only after a real submit.
     */
    fun approveRollout(project: Project, ref: PipelineRef, rollout: RolloutInfo): Boolean =
        decideRollout(project, ref, rollout, approved = true)

    /** Confirm, then reject [rollout]. Rejection is final — the rollout cannot be approved later. */
    fun rejectRollout(project: Project, ref: PipelineRef, rollout: RolloutInfo): Boolean =
        decideRollout(project, ref, rollout, approved = false)

    private fun decideRollout(project: Project, ref: PipelineRef, rollout: RolloutInfo, approved: Boolean): Boolean {
        if (!rollout.pendingApproval) {
            notify(project, NotificationType.WARNING, "Not awaiting approval",
                "${rollout.release} for ${rollout.targetId} is ${rollout.statePretty}.")
            return false
        }
        // A decision for this exact rollout is already running — ignore the duplicate.
        if (rollout.name in inFlight) return false

        val (dialogTitle, dialogMessage) = if (approved) {
            "Approve Rollout" to
                "Approve ${rollout.release} for ${rollout.targetId} in ${ref.pipeline}?"
        } else {
            "Reject Rollout" to
                "Reject ${rollout.release} for ${rollout.targetId} in ${ref.pipeline}?\n\n" +
                    "Rejection is final — the rollout cannot be approved later."
        }
        val confirmed = MessageDialogBuilder.yesNo(dialogTitle, dialogMessage).ask(project)
        if (!confirmed) return false
        // Claim the in-flight slot; lose the race (another decision dispatched first) → skip.
        if (!inFlight.add(rollout.name)) return false

        val model = PipelinesModel.getInstance(project)
        model.background(
            { CloudDeployApi.approveRollout(rollout.name, approved) },
            onComplete = { inFlight.remove(rollout.name) }, // always released, even on project-close cancel
        ) { result ->
            result.onSuccess {
                val past = if (approved) "approved" else "rejected"
                notify(project, NotificationType.INFORMATION, "Rollout $past",
                    "${rollout.release} $past for ${rollout.targetId}.")
                model.refresh(ref)
            }.onFailure {
                val verb = if (approved) "Approve" else "Reject"
                notify(project, NotificationType.ERROR, "$verb failed",
                    it.message ?: "Cloud Deploy request failed.")
                // Re-fetch the truth so the panel rebuilds with live (not stuck-disabled) buttons.
                model.refresh(ref)
            }
        }
        return true
    }

    /**
     * Retry a failed [rollout]'s job — the Cloud Console "Retry" semantics: it
     * resumes the SAME rollout. Confirms, then off the EDT fetches the rollout's
     * live detail to locate the failed phase/job (state may have moved since the
     * cached snapshot) and calls retryJob, finally refreshing. Returns true once
     * the request is dispatched (user confirmed, no duplicate in flight) — inline
     * buttons use this to disable only after a real submit.
     */
    fun retryRollout(project: Project, ref: PipelineRef, rollout: RolloutInfo): Boolean {
        if (!rollout.failed) {
            notify(project, NotificationType.WARNING, "Not failed",
                "${rollout.release} for ${rollout.targetId} is ${rollout.statePretty}.")
            return false
        }
        // A retry for this exact rollout is already running — ignore the duplicate.
        if (rollout.name in inFlightRetries) return false

        val confirmed = MessageDialogBuilder.yesNo(
            "Retry Rollout",
            "Retry the failed job in ${rollout.release} for ${rollout.targetId} in ${ref.pipeline}?",
        ).ask(project)
        if (!confirmed) return false
        // Claim the in-flight slot; lose the race (another retry dispatched first) → skip.
        if (!inFlightRetries.add(rollout.name)) return false

        val model = PipelinesModel.getInstance(project)
        model.background(
            {
                // The failed phase/job must come from the server, not the bounded cached
                // snapshot, so the phaseId/jobId match the rollout's current live state.
                val (phase, job) = CloudDeployApi.rolloutDetail(rollout.name).failedJob
                    ?: throw CloudDeployException("no failed job to retry — refresh and check the rollout's status")
                CloudDeployApi.retryJob(rollout.name, phase.id, job.id)
                "${job.label} job in phase ${phase.id}"
            },
            onComplete = { inFlightRetries.remove(rollout.name) }, // always released, even on project-close cancel
        ) { result ->
            result.onSuccess { what ->
                notify(project, NotificationType.INFORMATION, "Retry started",
                    "Retrying $what for ${rollout.targetId}.")
                model.refresh(ref)
            }.onFailure {
                notify(project, NotificationType.ERROR, "Retry failed",
                    it.message ?: "Cloud Deploy request failed.")
                // Re-fetch the truth so the panel rebuilds with live (not stuck) state.
                model.refresh(ref)
            }
        }
        return true
    }

    /** Copies the rollout's Cloud Console approval page (the /approve URL, where the Approve button lives). */
    fun copyApprovalLink(project: Project, rollout: RolloutInfo?, context: String) {
        val url = rollout?.approvalUrl ?: return notify(
            project, NotificationType.WARNING, "No rollout", "$context has no rollout to link to.",
        )
        CopyPasteManager.getInstance().setContents(StringSelection(url))
        notify(project, NotificationType.INFORMATION, "Approval link copied", url)
    }

    fun copyApprovalLink(project: Project, target: TargetState) =
        copyApprovalLink(project, target.latest, target.targetId)

    /** Copies the rollout's release name (e.g. "v4-331-4"). */
    fun copyVersion(project: Project, rollout: RolloutInfo?, context: String) {
        val release = rollout?.release ?: return notify(
            project, NotificationType.WARNING, "No version", "$context has never been deployed.",
        )
        CopyPasteManager.getInstance().setContents(StringSelection(release))
        notify(project, NotificationType.INFORMATION, "Version copied", release)
    }

    fun copyVersion(project: Project, target: TargetState) =
        copyVersion(project, target.latest, target.targetId)

    /** Copies a release name; used by release nodes. */
    fun copyRelease(project: Project, release: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(release))
        notify(project, NotificationType.INFORMATION, "Version copied", release)
    }

    /**
     * gcloud's rollout naming convention: `<release>-to-<target>-<seq>`. The
     * sequence is taken from the release's AUTHORITATIVE rollout list on the
     * server — not the bounded local snapshot, which can miss an old release's
     * prior rollouts and produce a colliding `-0001`. Runs off the EDT.
     */
    private fun nextRolloutId(ref: PipelineRef, release: String, targetId: String): String {
        val maxSeq = CloudDeployApi.releaseRollouts(ref, release)
            .filter { it.targetId == targetId }
            .mapNotNull { it.rolloutId.substringAfterLast('-').toIntOrNull() }
            .maxOrNull() ?: 0
        return "%s-to-%s-%04d".format(release, targetId, maxSeq + 1)
    }

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Delivery Pipeline")
            .createNotification(title, content, type)
            .notify(project)
    }
}
