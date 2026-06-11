package dev.flaticols.deliverypipeline.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.flaticols.deliverypipeline.PipelinesModel
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.RolloutInfo
import dev.flaticols.deliverypipeline.model.Snapshot
import dev.flaticols.deliverypipeline.model.TargetState
import dev.flaticols.deliverypipeline.model.prettyTime
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * Content shown in the Services view's details area. Actions (promote, copy
 * version/approval link, open console) live on the descriptor toolbar, not in
 * the panels — these are read-only summaries.
 */
object DetailPanels {

    // ---- pipeline -----------------------------------------------------------

    fun pipelinePanel(project: Project, ref: PipelineRef): JComponent =
        when (val snapshot = PipelinesModel.getInstance(project).snapshot(ref)) {
            is Snapshot.Loading -> message("Loading ${ref.pipeline}…")
            is Snapshot.Error -> message(snapshot.message)
            is Snapshot.Data -> pipelineDataPanel(ref, snapshot)
        }

    private fun pipelineDataPanel(ref: PipelineRef, data: Snapshot.Data): JComponent {
        val columns = arrayOf("Target", "Current", "Incoming", "State", "Approval", "Started", "Finished")
        val rows = data.targets.map { t ->
            arrayOf<Any>(
                t.targetId,
                t.current?.release ?: "—",
                if (t.hasIncoming) t.latest?.release.orEmpty() else "—",
                t.latest?.statePretty ?: "not deployed",
                approvalCell(t),
                prettyTime(t.latest?.createTime),
                prettyTime(t.latest?.deployEndTime),
            )
        }.toTypedArray()

        val panel = JPanel(BorderLayout())
        panel.add(header(AllIcons.Nodes.Deploy, ref.pipeline, "${ref.gcpProject}/${ref.region}"), BorderLayout.NORTH)
        panel.add(JBScrollPane(readOnlyTable(rows, columns)), BorderLayout.CENTER)
        approvalsBlock(data)?.let { panel.add(it, BorderLayout.SOUTH) }
        return panel
    }

    /** Pending approvals (what's waiting right now) + targets that gate future promotions. */
    private fun approvalsBlock(data: Snapshot.Data): JComponent? {
        val pending = data.targets.mapNotNull { t -> t.latest?.takeIf { it.pendingApproval }?.let { t to it } }
        val upcoming = data.targets
            .filter { it.requireApproval && it.latest?.pendingApproval != true }
            .map { it.targetId }
        if (pending.isEmpty() && upcoming.isEmpty()) return null

        val builder = FormBuilder.createFormBuilder()
        for ((target, rollout) in pending) {
            builder.addComponent(
                iconLabel(
                    "${target.targetId} — ${rollout.release} waiting since ${prettyTime(rollout.createTime)}",
                    AllIcons.Actions.Pause,
                ),
            )
        }
        if (upcoming.isNotEmpty()) {
            builder.addComponent(value("Approval also required for: ${upcoming.joinToString(", ")}"))
        }
        val panel = JPanel(BorderLayout())
        panel.add(header(AllIcons.General.Warning.takeIf { pending.isNotEmpty() }, "Approvals", ""), BorderLayout.NORTH)
        panel.add(builder.panel.apply { border = JBUI.Borders.empty(0, 14, 12, 14) }, BorderLayout.CENTER)
        return panel
    }

    // ---- target -------------------------------------------------------------

    fun targetPanel(project: Project, ref: PipelineRef, target: TargetState): JComponent {
        val latest = target.latest
        val current = target.current
        val currentText = current
            ?.let { "${it.release} · deployed ${prettyTime(it.deployEndTime.ifEmpty { it.createTime })}" }
            ?: "nothing deployed"
        val incoming: JComponent = if (target.hasIncoming) {
            iconLabel("${latest!!.release} · ${latest.statePretty}", latest.icon)
        } else {
            value("—")
        }

        val form = FormBuilder.createFormBuilder()
            // -- section 1: what is (and will be) running on this target ------
            .addComponent(TitledSeparator("Current State"))
            .addLabeledComponent("Version:", bold(currentText))
            .addLabeledComponent("Incoming:", incoming)
            .addLabeledComponent("State:", iconLabel(latest?.statePretty ?: "—", latest?.icon))
            .addLabeledComponent("Rollout:", value(latest?.rolloutId ?: "—"))
            .addLabeledComponent("Started:", value(prettyTime(latest?.createTime)))
            .addLabeledComponent("Finished:", value(prettyTime(latest?.deployEndTime)))
            .addLabeledComponent("Console:", BrowserLink("Open pipeline target", latest?.consoleUrl ?: ref.consoleUrl))
            // -- section 2: approval gate -------------------------------------
            .addComponent(TitledSeparator("Approval Required"))
            .addLabeledComponent("Required:", value(if (target.requireApproval) "yes" else "no"))
            .addLabeledComponent("Status:", approvalStatusLabel(target))
            .addLabeledComponent(
                "Waiting since:",
                value(if (latest?.pendingApproval == true) prettyTime(latest.createTime) else "—"),
            )
            .addLabeledComponent(
                "Approval page:",
                latest?.consoleUrl?.let { BrowserLink("Open rollout to approve", it) } ?: value("—"),
            )
            .panel
            .apply { border = JBUI.Borders.empty(2, 14, 12, 14) }

        return page(header(target.icon, target.targetId, "${ref.pipeline} · ${ref.gcpProject}/${ref.region}"), form)
    }

    private fun approvalStatusLabel(target: TargetState): JBLabel {
        val latest = target.latest ?: return value("—")
        return when {
            latest.pendingApproval -> iconLabel("pending now", AllIcons.General.Warning)
            latest.approvalState.isNotEmpty() ->
                value(latest.approvalState.lowercase().replace('_', ' '))
            else -> value("—")
        }
    }

    // ---- rollout ------------------------------------------------------------

    fun rolloutPanel(ref: PipelineRef, rollout: RolloutInfo): JComponent {
        val form = form(
            "Version:" to bold(rollout.release),
            "Target:" to value(rollout.targetId),
            "State:" to iconLabel(rollout.statePretty, rollout.icon),
            "Approval state:" to value(rollout.approvalState.ifEmpty { "—" }.lowercase().replace('_', ' ')),
            "Started:" to value(prettyTime(rollout.createTime)),
            "Finished:" to value(prettyTime(rollout.deployEndTime)),
            "Console:" to BrowserLink("Open rollout (approval page)", rollout.consoleUrl ?: ref.consoleUrl),
        )
        return page(header(rollout.icon, rollout.rolloutId, "${ref.pipeline} · ${ref.gcpProject}/${ref.region}"), form)
    }

    // ---- building blocks ----------------------------------------------------

    private fun page(header: JComponent, form: JComponent): JComponent {
        val top = JPanel(BorderLayout())
        top.add(form, BorderLayout.NORTH) // keep rows top-aligned
        val panel = JPanel(BorderLayout())
        panel.add(header, BorderLayout.NORTH)
        panel.add(top, BorderLayout.CENTER)
        return panel
    }

    private fun form(vararg rows: Pair<String, JComponent>): JComponent {
        val builder = FormBuilder.createFormBuilder()
        rows.forEach { (label, component) -> builder.addLabeledComponent(label, component) }
        return builder.panel.apply { border = JBUI.Borders.empty(2, 14, 12, 14) }
    }

    private fun header(icon: Icon?, title: String, subtitle: String): JComponent {
        val label = JBLabel("<html><b>$title</b>&nbsp;&nbsp;<font color='#808080'>$subtitle</font></html>")
        label.icon = icon
        label.border = JBUI.Borders.empty(10, 12, 6, 12)
        return label
    }

    private fun value(text: String): JBLabel = JBLabel(text)

    private fun bold(text: String): JBLabel = JBLabel("<html><b>$text</b></html>")

    private fun iconLabel(text: String, icon: Icon?): JBLabel =
        JBLabel(text, icon, SwingConstants.LEFT)

    private fun approvalCell(target: TargetState): String = when {
        !target.requireApproval -> "—"
        target.latest?.pendingApproval == true -> "PENDING"
        else -> "required"
    }

    private fun readOnlyTable(rows: Array<Array<Any>>, columns: Array<String>): JBTable {
        val model = object : DefaultTableModel(rows, columns) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        val table = JBTable(model)
        table.setShowGrid(false)
        return table
    }

    private fun message(text: String): JComponent = JBPanelWithEmptyText().withEmptyText(text)
}
