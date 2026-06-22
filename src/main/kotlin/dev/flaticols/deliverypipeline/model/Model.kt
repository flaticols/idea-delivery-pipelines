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

/**
 * Cloud Console approval page of a rollout — the rollout URL with a `/approve`
 * segment before the query (this is where the Console's Approve button lives).
 */
fun rolloutApprovalUrl(rolloutName: String): String? {
    val url = rolloutConsoleUrl(rolloutName) ?: return null
    val query = url.indexOf('?')
    return url.substring(0, query) + "/approve" + url.substring(query)
}

/**
 * Cloud Console page of one release, from its full resource name
 * `projects/{p}/locations/{l}/deliveryPipelines/{d}/releases/{r}`.
 */
fun releaseConsoleUrl(releaseName: String): String? {
    val parts = releaseName.split('/')
    if (parts.size < 8 || parts[0] != "projects") return null
    return "https://console.cloud.google.com/deploy/delivery-pipelines/" +
        "${parts[3]}/${parts[5]}/releases/${parts[7]}?project=${parts[1]}"
}

/**
 * Cloud Build logs page for the build that ran a rollout job, from the build's
 * resource name. Regional builds serialize as
 * `projects/{p}/locations/{loc}/builds/{id}` (the live Console uses the
 * `;region={loc}` matrix segment); a global build is `projects/{p}/builds/{id}`.
 * Null when [build] is empty or not a recognizable build resource name.
 */
fun cloudBuildLogUrl(build: String): String? {
    val parts = build.split('/')
    if (parts.size < 4 || parts[0] != "projects") return null
    val project = parts[1]
    return when {
        parts.size >= 6 && parts[2] == "locations" ->
            "https://console.cloud.google.com/cloud-build/builds;region=${parts[3]}/${parts[5]}?project=$project"
        else ->
            "https://console.cloud.google.com/cloud-build/builds/${parts[3]}?project=$project"
    }
}

/** One rollout from the Cloud Deploy API. */
data class RolloutInfo(
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
    /** The Cloud Console page with the Approve button (rollout URL + /approve). */
    val approvalUrl: String? get() = rolloutApprovalUrl(name)
    val pendingApproval: Boolean get() = state == "PENDING_APPROVAL"
    val failed: Boolean get() = state == "FAILED"
    val statePretty: String get() = state.lowercase().replace('_', ' ')
    val icon: Icon get() = rolloutStateIcon(state)
}

/**
 * One job inside a rollout phase. [kind] is the human stage ("deploy",
 * "verify", "predeploy", "postdeploy", "analysis", or a child-rollout job);
 * [jobRun] is the JobRun resource name (empty until the job has run), the
 * handle used to fetch the failure message and build logs.
 */
data class RolloutJob(
    val id: String,
    val state: String,
    val kind: String,
    val jobRun: String,
) {
    val failed: Boolean get() = state == "FAILED"
    val statePretty: String get() = state.lowercase().replace('_', ' ')
    val icon: Icon get() = rolloutStateIcon(state)
    /** Stage label for the UI: the readable [kind], falling back to the raw id. */
    val label: String get() = kind.ifEmpty { id }
}

/** One phase of a rollout with its jobs (deployment jobs, or child-rollout jobs for canary). */
data class RolloutPhase(
    val id: String,
    val state: String,
    val jobs: List<RolloutJob>,
) {
    val failed: Boolean get() = state == "FAILED"
    val statePretty: String get() = state.lowercase().replace('_', ' ')
    val icon: Icon get() = rolloutStateIcon(state)
}

/**
 * Full detail of one rollout (phases + their jobs), fetched on demand for the
 * status panel and to locate the exact phase/job that `retryJob` must target.
 */
data class RolloutDetail(
    val name: String,
    val targetId: String,
    val state: String,
    /** Rollout-level failure summary (`failureReason`); "" when not failed. */
    val failureReason: String,
    /** Rollout-level failure cause enum (`deployFailureCause`); "" when absent. */
    val deployFailureCause: String,
    val createTime: String,
    val deployStartTime: String,
    val deployEndTime: String,
    val phases: List<RolloutPhase>,
) {
    val statePretty: String get() = state.lowercase().replace('_', ' ')
    val icon: Icon get() = rolloutStateIcon(state)

    /**
     * The first FAILED job (phases then jobs, in execution order) — exactly what
     * the Cloud Console "Retry" button resumes. Null when nothing is retryable.
     */
    val failedJob: Pair<RolloutPhase, RolloutJob>?
        get() = phases.firstNotNullOfOrNull { phase ->
            phase.jobs.firstOrNull { it.failed }?.let { phase to it }
        }

    /** A failed rollout with a concrete failed job to retry. */
    val retryable: Boolean get() = state == "FAILED" && failedJob != null
}

/** The run of one rollout job: its failure message and the Cloud Build it ran. */
data class JobRunDetail(
    val name: String,
    val state: String,
    /** The job-run's own failure message; "" when it did not fail / not reported. */
    val failureMessage: String,
    /** Cloud Build resource name that executed the job; "" for non-Cloud-Build jobs. */
    val build: String,
) {
    val buildLogUrl: String? get() = cloudBuildLogUrl(build)
}

/** The current credentials' rollout permissions on a pipeline, probed in one call. */
data class RolloutPermissions(val canApprove: Boolean, val canRetry: Boolean)

/** One Cloud Deploy release (a versioned set of rendered manifests), newest first. */
data class ReleaseInfo(
    /** Full resource name (…/releases/{r}). */
    val name: String,
    val createTime: String,
    val abandoned: Boolean,
    /** Render outcome: SUCCEEDED / FAILED / IN_PROGRESS / "" (unknown). */
    val renderState: String,
) {
    val release: String get() = name.substringAfterLast('/')
    val consoleUrl: String? get() = releaseConsoleUrl(name)
}

/** One pipeline stage with its rollout history (newest first). */
data class TargetState(
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
    data class Error(val message: String) : Snapshot
    data class Data(
        val targets: List<TargetState>,
        /** Whether the current user may approve/reject here: true/false, or null when unknown. */
        val canApprove: Boolean? = null,
        /** Whether the current user may retry failed jobs here: true/false, or null when unknown. */
        val canRetry: Boolean? = null,
        /** Recent releases, newest first — the pipeline's "Releases" tree node. */
        val releases: List<ReleaseInfo> = emptyList(),
    ) : Snapshot {
        /**
         * Each target whose latest rollout is on its way rather than serving:
         * awaiting approval, or actively rolling out. Pairs carry the target for
         * UI context. Awaiting-approval first, then newest first. Drives the
         * pipeline-wide "incoming rollouts" view — note this is keyed off the
         * latest rollout's *state*, not [TargetState.hasIncoming], so a target's
         * first-ever rollout (no prior succeeded version) is still listed.
         */
        val incomingRollouts: List<Pair<TargetState, RolloutInfo>>
            get() = targets.mapNotNull { t ->
                val latest = t.latest ?: return@mapNotNull null
                if (latest.pendingApproval || latest.state in INCOMING_STATES) t to latest else null
            }.sortedWith(
                compareByDescending<Pair<TargetState, RolloutInfo>> { it.second.pendingApproval }
                    .thenByDescending { it.second.createTime },
            )

        /** The subset of [incomingRollouts] that is blocked on approval right now. */
        val pendingApprovals: List<Pair<TargetState, RolloutInfo>>
            get() = incomingRollouts.filter { it.second.pendingApproval }

        /** Pipeline-wide count of rollouts awaiting approval (tree subtitle). */
        val pendingApprovalCount: Int get() = pendingApprovals.size

        private companion object {
            /** Rollout states that mean "newer version on the way" (excludes PENDING_APPROVAL). */
            val INCOMING_STATES = setOf("IN_PROGRESS", "PENDING", "PENDING_RELEASE")
        }
    }
}
