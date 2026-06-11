package dev.flaticols.deliverypipeline.services

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.execution.services.ServiceViewProvidingContributor
import com.intellij.execution.services.SimpleServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.flaticols.deliverypipeline.PipelinesModel
import dev.flaticols.deliverypipeline.PipelinesStore
import dev.flaticols.deliverypipeline.actions.AddPipelineAction
import dev.flaticols.deliverypipeline.actions.Promotions
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.RolloutInfo
import dev.flaticols.deliverypipeline.model.Snapshot
import dev.flaticols.deliverypipeline.model.TargetState
import dev.flaticols.deliverypipeline.model.prettyTime
import dev.flaticols.deliverypipeline.ui.DetailPanels
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Contributes "Delivery Pipelines" to the platform Services tool window:
 * one node per watched pipeline, expanding into its targets with the latest
 * rollout state per target.
 */
class PipelinesServiceContributor : ServiceViewContributor<PipelineNode> {

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        object : SimpleServiceViewDescriptor("Delivery Pipelines", AllIcons.Nodes.Deploy) {
            override fun getToolbarActions(): ActionGroup = rootActions(project)
            override fun getPopupActions(): ActionGroup = rootActions(project)
        }

    override fun getServices(project: Project): List<PipelineNode> {
        val model = PipelinesModel.getInstance(project)
        return PipelinesStore.getInstance(project).refs().map { ref ->
            model.ensureLoaded(ref)
            PipelineNode(ref)
        }
    }

    override fun getServiceDescriptor(project: Project, service: PipelineNode): ServiceViewDescriptor =
        PipelineDescriptor(project, service.ref)

    private fun rootActions(project: Project): ActionGroup = DefaultActionGroup(
        object : DumbAwareAction("Add Delivery Pipeline…", null, AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) = AddPipelineAction.perform(project)
        },
        object : DumbAwareAction("Refresh All", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = PipelinesModel.getInstance(project).refreshAll()
        },
    )
}

/** A watched pipeline; contributes its targets as child services. */
class PipelineNode(val ref: PipelineRef) : ServiceViewProvidingContributor<TargetNode, PipelineNode> {

    override fun asService(): PipelineNode = this

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        PipelineDescriptor(project, ref)

    override fun getServices(project: Project): List<TargetNode> =
        (PipelinesModel.getInstance(project).snapshot(ref) as? Snapshot.Data)
            ?.targets?.map { TargetNode(ref, it) }.orEmpty()

    override fun getServiceDescriptor(project: Project, service: TargetNode): ServiceViewDescriptor =
        TargetDescriptor(project, service)

    override fun equals(other: Any?): Boolean = other is PipelineNode && other.ref == ref
    override fun hashCode(): Int = ref.hashCode()
}

/**
 * One pipeline stage; identity = (pipeline, targetId) so refreshes keep tree
 * state. Contributes its recent rollouts as child nodes.
 */
class TargetNode(val ref: PipelineRef, val target: TargetState) :
    ServiceViewProvidingContributor<RolloutNode, TargetNode> {

    override fun asService(): TargetNode = this

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        TargetDescriptor(project, this)

    override fun getServices(project: Project): List<RolloutNode> =
        target.history.map { RolloutNode(ref, it) }

    override fun getServiceDescriptor(project: Project, service: RolloutNode): ServiceViewDescriptor =
        RolloutDescriptor(project, service)

    override fun equals(other: Any?): Boolean =
        other is TargetNode && other.ref == ref && other.target.targetId == target.targetId

    override fun hashCode(): Int = 31 * ref.hashCode() + target.targetId.hashCode()
}

/** One historical rollout under a target; identity = full rollout resource name. */
class RolloutNode(val ref: PipelineRef, val rollout: RolloutInfo) {
    override fun equals(other: Any?): Boolean = other is RolloutNode && other.rollout.name == rollout.name
    override fun hashCode(): Int = rollout.name.hashCode()
}

private class PipelineDescriptor(
    private val project: Project,
    private val ref: PipelineRef,
) : ServiceViewDescriptor {

    override fun getPresentation(): ItemPresentation {
        val origin = "${ref.gcpProject}/${ref.region}"
        return when (val snapshot = PipelinesModel.getInstance(project).snapshot(ref)) {
            is Snapshot.Loading ->
                PresentationData(ref.pipeline, "$origin · loading…", AllIcons.Nodes.Deploy, null)
            is Snapshot.Error ->
                PresentationData(ref.pipeline, "$origin · ${snapshot.message}", AllIcons.General.Warning, null)
            is Snapshot.Data -> {
                val ok = snapshot.targets.count { it.state == "SUCCEEDED" }
                val pending = snapshot.targets.count { it.latest?.pendingApproval == true }
                val approvals = if (pending > 0) " · $pending awaiting approval" else ""
                PresentationData(
                    ref.pipeline,
                    "$origin · $ok/${snapshot.targets.size} succeeded$approvals",
                    AllIcons.Nodes.Deploy,
                    null,
                )
            }
        }
    }

    /** The pipeline overview (all targets, versions, approvals) in the details area. */
    override fun getContentComponent(): JComponent = DetailPanels.pipelinePanel(project, ref)

    /** Shown in the details-area toolbar; the right-click popup inherits it. */
    override fun getToolbarActions(): ActionGroup = DefaultActionGroup(
        object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = PipelinesModel.getInstance(project).refresh(ref)
        },
        object : DumbAwareAction("Open in Cloud Console", null, AllIcons.General.Web) {
            override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(ref.consoleUrl)
        },
    )

    /** Lets the Services view's native delete (⌫) unwatch the pipeline. */
    override fun getRemover(): Runnable = Runnable {
        PipelinesStore.getInstance(project).remove(ref)
        PipelinesModel.getInstance(project).drop(ref)
    }

    override fun handleDoubleClick(event: MouseEvent): Boolean {
        BrowserUtil.browse(ref.consoleUrl)
        return true
    }
}

private class TargetDescriptor(
    private val project: Project,
    private val node: TargetNode,
) : ServiceViewDescriptor {

    override fun getPresentation(): ItemPresentation =
        PresentationData(node.target.targetId, node.target.display, node.target.icon, null)

    /** Target details in the details area (history lives in the tree). */
    override fun getContentComponent(): JComponent =
        DetailPanels.targetPanel(project, node.ref, node.target)

    /** Shown in the details-area toolbar; the right-click popup inherits it. */
    override fun getToolbarActions(): ActionGroup = DefaultActionGroup(
        object : DumbAwareAction("Promote to This Target…", "Promote the previous stage's release here", AllIcons.Actions.Forward) {
            override fun actionPerformed(e: AnActionEvent) =
                Promotions.promoteToTarget(project, node.ref, node.target.targetId)
        },
        object : DumbAwareAction("Copy Version", "Copy the current release name", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) =
                Promotions.copyVersion(project, node.target)
        },
        object : DumbAwareAction("Copy Approval Link", "Copy the rollout's Cloud Console URL", AllIcons.Ide.Link) {
            override fun actionPerformed(e: AnActionEvent) =
                Promotions.copyApprovalLink(project, node.target)
        },
        object : DumbAwareAction("Open in Cloud Console", null, AllIcons.General.Web) {
            override fun actionPerformed(e: AnActionEvent) =
                BrowserUtil.browse(node.target.latest?.consoleUrl ?: node.ref.consoleUrl)
        },
        object : DumbAwareAction("Refresh Pipeline", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) =
                PipelinesModel.getInstance(project).refresh(node.ref)
        },
    )

    override fun handleDoubleClick(event: MouseEvent): Boolean {
        BrowserUtil.browse(node.target.latest?.consoleUrl ?: node.ref.consoleUrl)
        return true
    }
}

private class RolloutDescriptor(
    private val project: Project,
    private val node: RolloutNode,
) : ServiceViewDescriptor {

    override fun getPresentation(): ItemPresentation =
        PresentationData(
            node.rollout.release,
            "${node.rollout.statePretty} · ${prettyTime(node.rollout.createTime)}",
            node.rollout.icon,
            null,
        )

    /** Rollout details in the details area. */
    override fun getContentComponent(): JComponent =
        DetailPanels.rolloutPanel(node.ref, node.rollout)

    /** Shown in the details-area toolbar; the right-click popup inherits it. */
    override fun getToolbarActions(): ActionGroup = DefaultActionGroup(
        object : DumbAwareAction("Copy Version", "Copy this rollout's release name", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) =
                Promotions.copyVersion(project, node.rollout, node.rollout.rolloutId)
        },
        object : DumbAwareAction("Copy Approval Link", "Copy this rollout's Cloud Console URL", AllIcons.Ide.Link) {
            override fun actionPerformed(e: AnActionEvent) =
                Promotions.copyApprovalLink(project, node.rollout, node.rollout.rolloutId)
        },
        object : DumbAwareAction("Open in Cloud Console", null, AllIcons.General.Web) {
            override fun actionPerformed(e: AnActionEvent) =
                BrowserUtil.browse(node.rollout.consoleUrl ?: node.ref.consoleUrl)
        },
    )

    override fun handleDoubleClick(event: MouseEvent): Boolean {
        BrowserUtil.browse(node.rollout.consoleUrl ?: node.ref.consoleUrl)
        return true
    }
}
