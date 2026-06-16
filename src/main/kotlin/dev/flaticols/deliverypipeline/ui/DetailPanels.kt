package dev.flaticols.deliverypipeline.ui

import com.intellij.ide.HelpTooltip
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
import dev.flaticols.deliverypipeline.actions.Promotions
import dev.flaticols.deliverypipeline.gcloud.CloudDeployApi
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.ReleaseInfo
import dev.flaticols.deliverypipeline.model.RolloutInfo
import dev.flaticols.deliverypipeline.model.Snapshot
import dev.flaticols.deliverypipeline.model.TargetState
import dev.flaticols.deliverypipeline.model.prettyTime
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * Content shown in the Services view's details area. Most actions (promote,
 * copy version/approval link, open console) live on the descriptor toolbar;
 * the panels are read-only summaries, except the approval gate — pending
 * rollouts expose inline Approve/Reject buttons here (and in the toolbar).
 */
object DetailPanels {
    fun pipelinePanel(project: Project, ref: PipelineRef): JComponent =
        when (val snapshot = PipelinesModel.getInstance(project).snapshot(ref)) {
            is Snapshot.Loading -> message("Loading ${ref.pipeline}…")
            is Snapshot.Error -> message(snapshot.message)
            is Snapshot.Data -> pipelineDataPanel(project, ref, snapshot)
        }

    private fun pipelineDataPanel(project: Project, ref: PipelineRef, data: Snapshot.Data): JComponent {
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
        approvalsBlock(project, ref, data)?.let { panel.add(it, BorderLayout.SOUTH) }
        return panel
    }

    /**
     * The pipeline-wide incoming-rollouts rollup: every rollout on its way
     * (awaiting approval — with inline Approve/Reject — or rolling out), plus a
     * line naming targets that gate future promotions but have nothing pending.
     */
    private fun approvalsBlock(project: Project, ref: PipelineRef, data: Snapshot.Data): JComponent? {
        val incoming = data.incomingRollouts
        val upcoming = data.targets
            .filter { it.requireApproval && it.latest?.pendingApproval != true }
            .map { it.targetId }
        if (incoming.isEmpty() && upcoming.isEmpty()) return null

        val builder = FormBuilder.createFormBuilder()
        for ((target, rollout) in incoming) {
            builder.addComponent(incomingRow(project, ref, target, rollout, data.canApprove))
        }
        if (data.pendingApprovalCount > 0 && data.canApprove == false) {
            builder.addComponent(iconLabel("You cannot approve here — $MISSING_APPROVE_PERMISSION.", AllIcons.General.Warning))
        }
        if (upcoming.isNotEmpty()) {
            builder.addComponent(value("Approval also required for: ${upcoming.joinToString(", ")}"))
        }
        val pending = if (data.pendingApprovalCount > 0) " · approvals pending" else ""
        val title = if (incoming.isEmpty()) "Approvals" else "Incoming (${incoming.size})$pending"
        val panel = JPanel(BorderLayout())
        panel.add(header(AllIcons.General.Warning.takeIf { data.pendingApprovalCount > 0 }, title, ""), BorderLayout.NORTH)
        panel.add(builder.panel.apply { border = JBUI.Borders.empty(0, 14, 12, 14) }, BorderLayout.CENTER)
        return panel
    }

    /** One incoming-rollout row: status label, plus inline Approve/Reject when it awaits approval. */
    private fun incomingRow(project: Project, ref: PipelineRef, target: TargetState, rollout: RolloutInfo, canApprove: Boolean?): JComponent {
        return JPanel(BorderLayout()).apply {
            if (rollout.pendingApproval) {
                add(
                    iconLabel("${target.targetId} — ${rollout.release} waiting since ${prettyTime(rollout.createTime)}", AllIcons.Actions.Pause),
                    BorderLayout.CENTER,
                )
                add(approvalButtons(project, ref, rollout, canApprove), BorderLayout.EAST)
            } else {
                add(iconLabel("${target.targetId} — ${rollout.release} · ${rollout.statePretty}", rollout.icon), BorderLayout.CENTER)
            }
        }
    }

    /**
     * [Approve] [Reject] pair. Disabled with an explanatory tooltip when the
     * user is known to lack the approve permission; otherwise a click disables
     * both once the request is dispatched, guarding against double-submit.
     */
    private fun approvalButtons(project: Project, ref: PipelineRef, rollout: RolloutInfo, canApprove: Boolean?): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val approve = JButton("Approve")
        val reject = JButton("Reject")
        if (canApprove == false) {
            approve.isEnabled = false
            reject.isEnabled = false
            // HelpTooltip (not raw toolTipText): stable, readable, and stays put on hover.
            val why = MISSING_APPROVE_PERMISSION.replaceFirstChar { it.uppercase() }
            HelpTooltip().setDescription(why).setNeverHideOnTimeout(true).installOn(approve)
            HelpTooltip().setDescription(why).setNeverHideOnTimeout(true).installOn(reject)
        } else {
            val disableBoth = {
                approve.isEnabled = false
                reject.isEnabled = false
            }
            approve.addActionListener { if (Promotions.approveRollout(project, ref, rollout)) disableBoth() }
            reject.addActionListener { if (Promotions.rejectRollout(project, ref, rollout)) disableBoth() }
        }
        panel.add(approve)
        panel.add(reject)
        return panel
    }

    fun releasesPanel(project: Project, ref: PipelineRef): JComponent =
        when (val snapshot = PipelinesModel.getInstance(project).snapshot(ref)) {
            is Snapshot.Loading -> message("Loading ${ref.pipeline}…")
            is Snapshot.Error -> message(snapshot.message)
            is Snapshot.Data -> releasesDataPanel(ref, snapshot)
        }

    private fun releasesDataPanel(ref: PipelineRef, data: Snapshot.Data): JComponent {
        if (data.releases.isEmpty()) return message("No releases in ${ref.pipeline}.")
        val columns = arrayOf("Release", "Created", "Render", "Serving now")
        val rows = data.releases.map { r ->
            val serving = data.targets.filter { it.current?.release == r.release }.map { it.targetId }
            arrayOf<Any>(
                r.release,
                prettyTime(r.createTime),
                if (r.abandoned) "abandoned" else r.renderState.lowercase().replace('_', ' ').ifEmpty { "—" },
                serving.joinToString(", ").ifEmpty { "—" },
            )
        }.toTypedArray()

        val panel = JPanel(BorderLayout())
        panel.add(header(AllIcons.Nodes.Artifact, "Releases", "${ref.pipeline} · ${data.releases.size} recent"), BorderLayout.NORTH)
        panel.add(JBScrollPane(readOnlyTable(rows, columns)), BorderLayout.CENTER)
        return panel
    }

    fun releasePanel(project: Project, ref: PipelineRef, release: ReleaseInfo): JComponent {
        val data = PipelinesModel.getInstance(project).snapshot(ref) as? Snapshot.Data
        val serving = data?.targets.orEmpty()
            .filter { it.current?.release == release.release }.map { it.targetId }
        // Filled in asynchronously from the release's authoritative rollout list.
        val rolloutsLabel = value("loading…")
        // Promote lives on the toolbar / right-click (no inline button), leaving room for status.
        val form = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Release"))
            .addLabeledComponent("Version:", bold(release.release))
            .addLabeledComponent("Created:", value(prettyTime(release.createTime)))
            .addLabeledComponent("Render:", value(release.renderState.lowercase().replace('_', ' ').ifEmpty { "—" }))
            .addLabeledComponent("Abandoned:", value(if (release.abandoned) "yes" else "no"))
            .addLabeledComponent("Serving now:", value(serving.joinToString(", ").ifEmpty { "—" }))
            .addLabeledComponent("Rollouts:", rolloutsLabel)
            .addLabeledComponent("Console:", BrowserLink("Open release", release.consoleUrl ?: ref.consoleUrl))
            .panel
            .apply { border = JBUI.Borders.empty(2, 14, 12, 14) }

        // Per-target deploy status for THIS release, fetched off the EDT from the release's own
        // rollouts — the cached snapshot's bounded per-target history can miss an older release
        // (e.g. v4-331-5 once superseded on rx-int) and would otherwise show nothing.
        PipelinesModel.getInstance(project).background({
            CloudDeployApi.releaseRollouts(ref, release.release)
                .groupBy { it.targetId }
                .toSortedMap()
                .map { (target, rollouts) ->
                    val newest = rollouts.maxByOrNull { it.createTime }
                    "$target ${newest?.statePretty ?: "—"}"
                }
                .joinToString(" · ")
                .ifEmpty { "not deployed to any target" }
        }) { result ->
            rolloutsLabel.text = result.getOrElse { "unavailable — ${it.message}" }
        }

        return page(header(AllIcons.Nodes.Artifact, release.release, "${ref.pipeline} · ${ref.gcpProject}/${ref.region}"), form)
    }

    fun targetPanel(project: Project, ref: PipelineRef, target: TargetState): JComponent {
        val latest = target.latest
        val current = target.current
        val canApprove = (PipelinesModel.getInstance(project).snapshot(ref) as? Snapshot.Data)?.canApprove
        val currentText = current
            ?.let { "${it.release} · deployed ${prettyTime(it.deployEndTime.ifEmpty { it.createTime })}" }
            ?: "nothing deployed"
        val incoming: JComponent = if (target.hasIncoming) {
            iconLabel("${latest!!.release} · ${latest.statePretty}", latest.icon)
        } else {
            value("—")
        }

        val builder = FormBuilder.createFormBuilder()
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
            .addLabeledComponent("Your permission:", value(approvePermissionText(canApprove)))
            .addLabeledComponent(
                "Waiting since:",
                value(if (latest?.pendingApproval == true) prettyTime(latest.createTime) else "—"),
            )
            .addLabeledComponent(
                "Approval page:",
                latest?.approvalUrl?.let { BrowserLink("Open rollout to approve", it) } ?: value("—"),
            )
        // Inline Approve/Reject, only while this target's rollout actually awaits approval.
        if (latest?.pendingApproval == true) {
            builder.addLabeledComponent("Actions:", approvalButtons(project, ref, latest, canApprove))
        }
        val form = builder.panel.apply { border = JBUI.Borders.empty(2, 14, 12, 14) }

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

    fun rolloutPanel(ref: PipelineRef, rollout: RolloutInfo): JComponent {
        val form = form(
            "Version:" to bold(rollout.release),
            "Target:" to value(rollout.targetId),
            "State:" to iconLabel(rollout.statePretty, rollout.icon),
            "Approval state:" to value(rollout.approvalState.ifEmpty { "—" }.lowercase().replace('_', ' ')),
            "Started:" to value(prettyTime(rollout.createTime)),
            "Finished:" to value(prettyTime(rollout.deployEndTime)),
            "Console:" to BrowserLink("Open rollout (approval page)", rollout.approvalUrl ?: rollout.consoleUrl ?: ref.consoleUrl),
        )
        return page(header(rollout.icon, rollout.rolloutId, "${ref.pipeline} · ${ref.gcpProject}/${ref.region}"), form)
    }

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

    private fun approvePermissionText(canApprove: Boolean?): String = when (canApprove) {
        true -> "yes"
        false -> "no — $MISSING_APPROVE_PERMISSION"
        null -> "unknown"
    }

    private fun message(text: String): JComponent = JBPanelWithEmptyText().withEmptyText(text)

    /** Reason text reused by the disabled buttons, the panel warning, and the target permission row. */
    private const val MISSING_APPROVE_PERMISSION = "you lack the clouddeploy.rollouts.approve permission"
}
