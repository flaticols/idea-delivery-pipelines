package dev.flaticols.deliverypipeline.model

import com.intellij.icons.AllIcons
import com.intellij.util.text.DateFormatUtil
import java.time.Instant
import javax.swing.Icon

/** Editor icon for a Cloud Deploy rollout state ("" = never deployed). */
fun rolloutStateIcon(state: String): Icon = when (state) {
    "SUCCEEDED" -> AllIcons.RunConfigurations.TestPassed
    "FAILED", "APPROVAL_REJECTED" -> AllIcons.RunConfigurations.TestFailed
    "PENDING_APPROVAL" -> AllIcons.Actions.Pause
    "IN_PROGRESS", "PENDING", "PENDING_RELEASE", "CANCELLING" -> AllIcons.Actions.Execute
    else -> AllIcons.RunConfigurations.TestIgnored // cancelled, halted, never deployed
}

/** "Today 07:32"-style timestamp from an API ISO instant; "—" when absent. */
fun prettyTime(iso: String?): String {
    if (iso.isNullOrEmpty()) return "—"
    return runCatching { DateFormatUtil.formatPrettyDateTime(Instant.parse(iso).toEpochMilli()) }
        .getOrDefault(iso)
}

/** One watched Cloud Deploy delivery pipeline: GCP project + region + pipeline id. */
data class PipelineRef(val gcpProject: String, val region: String, val pipeline: String) {
    /** REST resource path under the Cloud Deploy v1 API. */
    val resourcePath: String
        get() = "projects/$gcpProject/locations/$region/deliveryPipelines/$pipeline"

    val consoleUrl: String
        get() = "https://console.cloud.google.com/deploy/delivery-pipelines/$region/$pipeline?project=$gcpProject"

    override fun toString(): String = "$pipeline [$gcpProject/$region]"
}

/**
 * Cloud Console page of one rollout (where the Approve button lives), derived
 * from the full resource name
 * `projects/{p}/locations/{l}/deliveryPipelines/{d}/releases/{r}/rollouts/{ro}`.
 */
fun rolloutConsoleUrl(rolloutName: String): String? {
    val parts = rolloutName.split('/')
    if (parts.size < 10 || parts[0] != "projects") return null
    return "https://console.cloud.google.com/deploy/delivery-pipelines/" +
        "${parts[3]}/${parts[5]}/releases/${parts[7]}/rollouts/${parts[9]}?project=${parts[1]}"
}

/** One rollout from the Cloud Deploy API. */
class RolloutInfo(
    /** Full resource name (…/releases/{r}/rollouts/{ro}). */
    val name: String,
    val targetId: String,
    val state: String,
    val approvalState: String,
    val release: String,
    val createTime: String,
    val deployEndTime: String,
) {
    val rolloutId: String get() = name.substringAfterLast('/')
    val consoleUrl: String? get() = rolloutConsoleUrl(name)
    val pendingApproval: Boolean get() = state == "PENDING_APPROVAL"
    val statePretty: String get() = state.lowercase().replace('_', ' ')
    val icon: Icon get() = rolloutStateIcon(state)
}

/** One pipeline stage with its rollout history (newest first). */
class TargetState(
    val targetId: String,
    /** The target resource's requireApproval flag. */
    val requireApproval: Boolean,
    /** Newest rollout, or null if never deployed. */
    val latest: RolloutInfo?,
    /** Newest SUCCEEDED rollout — the version actually serving on this target. */
    val current: RolloutInfo?,
    /** Recent rollouts, newest first (capped). */
    val history: List<RolloutInfo>,
) {
    val state: String get() = latest?.state ?: ""

    val icon: Icon get() = rolloutStateIcon(state)

    /** True when something newer than the deployed version is on the way (or stuck). */
    val hasIncoming: Boolean
        get() = latest != null && current != null && latest.name != current.name

    /** "v4-331-4 · succeeded", or "v4-280-1 → v4-294-25 · pending approval" while incoming. */
    val display: String
        get() {
            val latest = latest ?: return "not deployed"
            return if (hasIncoming) {
                "${current!!.release} → ${latest.release} · ${latest.statePretty}"
            } else {
                "${latest.release} · ${latest.statePretty}"
            }
        }
}

/** Per-pipeline fetch state shown in the Services tree. */
sealed interface Snapshot {
    object Loading : Snapshot
    class Error(val message: String) : Snapshot
    class Data(val targets: List<TargetState>) : Snapshot
}
