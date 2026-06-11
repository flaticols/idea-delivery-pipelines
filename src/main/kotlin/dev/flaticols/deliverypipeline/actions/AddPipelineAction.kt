package dev.flaticols.deliverypipeline.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.flaticols.deliverypipeline.PipelinesModel
import dev.flaticols.deliverypipeline.PipelinesStore

/**
 * "GCP Delivery Pipelines…" in the Services view's + (Add Service) popup and
 * the Tools menu: pick pipelines from any GCP project/region and watch them.
 */
class AddPipelineAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(Companion::perform)
    }

    companion object {
        fun perform(project: Project) {
            val dialog = AddPipelineDialog(project)
            if (!dialog.showAndGet()) return
            val store = PipelinesStore.getInstance(project)
            dialog.selectedRefs().forEach(store::add)
            PipelinesModel.getInstance(project).structureChanged()
        }
    }
}
