package dev.flaticols.deliverypipeline.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.util.concurrency.AppExecutorUtil
import dev.flaticols.deliverypipeline.PipelinesModel
import dev.flaticols.deliverypipeline.gcloud.CloudDeployApi
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.RolloutInfo
import dev.flaticols.deliverypipeline.model.Snapshot
import dev.flaticols.deliverypipeline.model.TargetState
import java.awt.datatransfer.StringSelection

/** Promote / copy-link / copy-version operations shared by popup actions and detail-panel buttons. */
object Promotions {

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

    /** Confirm, then create the promotion rollout via the API and refresh. */
    fun promoteToTarget(project: Project, ref: PipelineRef, targetId: String) {
        val data = PipelinesModel.getInstance(project).snapshot(ref) as? Snapshot.Data ?: return
        val source = promotionSource(data, targetId)
        if (source == null) {
            notify(project, NotificationType.WARNING, "Nothing to promote",
                "No release on the stage before $targetId in ${ref.pipeline}.")
            return
        }
        val confirmed = MessageDialogBuilder.yesNo(
            "Promote Release",
            "Promote ${source.release} to $targetId in ${ref.pipeline}?",
        ).ask(project)
        if (!confirmed) return

        val rolloutId = nextRolloutId(data, source.release, targetId)
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { CloudDeployApi.promote(ref, source.release, targetId, rolloutId) }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess {
                    notify(project, NotificationType.INFORMATION, "Promotion started",
                        "Created rollout $rolloutId.")
                    PipelinesModel.getInstance(project).refresh(ref)
                }.onFailure {
                    notify(project, NotificationType.ERROR, "Promotion failed",
                        it.message ?: "Cloud Deploy request failed.")
                }
            }
        }
    }

    /** Copies the rollout's Cloud Console page (where the Approve button lives). */
    fun copyApprovalLink(project: Project, rollout: RolloutInfo?, context: String) {
        val url = rollout?.consoleUrl
        if (url == null) {
            notify(project, NotificationType.WARNING, "No rollout", "$context has no rollout to link to.")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(url))
        notify(project, NotificationType.INFORMATION, "Approval link copied", url)
    }

    fun copyApprovalLink(project: Project, target: TargetState) =
        copyApprovalLink(project, target.latest, target.targetId)

    /** Copies the rollout's release name (e.g. "v4-331-4"). */
    fun copyVersion(project: Project, rollout: RolloutInfo?, context: String) {
        val release = rollout?.release
        if (release == null) {
            notify(project, NotificationType.WARNING, "No version", "$context has never been deployed.")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(release))
        notify(project, NotificationType.INFORMATION, "Version copied", release)
    }

    fun copyVersion(project: Project, target: TargetState) =
        copyVersion(project, target.latest, target.targetId)

    /** gcloud's rollout naming convention: <release>-to-<target>-<seq>. */
    private fun nextRolloutId(data: Snapshot.Data, release: String, targetId: String): String {
        val maxSeq = data.targets.asSequence()
            .flatMap { it.history }
            .filter { it.release == release && it.targetId == targetId }
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
