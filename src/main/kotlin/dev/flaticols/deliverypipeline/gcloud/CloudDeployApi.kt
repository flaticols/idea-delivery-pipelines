package dev.flaticols.deliverypipeline.gcloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.flaticols.deliverypipeline.model.JobRunDetail
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.ReleaseInfo
import dev.flaticols.deliverypipeline.model.RolloutDetail
import dev.flaticols.deliverypipeline.model.RolloutInfo
import dev.flaticols.deliverypipeline.model.RolloutJob
import dev.flaticols.deliverypipeline.model.RolloutPermissions
import dev.flaticols.deliverypipeline.model.RolloutPhase
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Direct HTTPS access to the Cloud Deploy v1 REST API (clouddeploy.googleapis.com)
 * with the gcloud-issued bearer token from [GcloudAuth]. Built on the JDK
 * HttpClient + the platform-bundled Gson — no Google SDK dependencies.
 *
 * API facts verified live: list endpoints reject `orderBy` ("cannot sort on
 * field") but return newest-first by default; the AIP-159 aggregated
 * `releases/-` wildcard works.
 */
object CloudDeployApi {

    /** Active GCP project ids via Cloud Resource Manager (same bearer token). */
    fun listProjects(): List<String> =
        getJson("https://cloudresourcemanager.googleapis.com/v1/projects?pageSize=1000&filter=${enc("lifecycleState:ACTIVE")}")
            .arr("projects")
            .mapNotNull { (it as? JsonObject)?.str("projectId") }
            .sorted()

    fun listPipelines(gcpProject: String, region: String): List<String> =
        getJson("$BASE/projects/$gcpProject/locations/$region/deliveryPipelines?pageSize=1000")
            .arr("deliveryPipelines")
            .mapNotNull { (it as? JsonObject)?.str("name")?.substringAfterLast('/') }

    /** Ordered targetIds of the pipeline's serial stages. */
    fun pipelineStages(ref: PipelineRef): List<String> =
        getJson("$BASE/${ref.resourcePath}")
            .obj("serialPipeline")
            .arr("stages")
            .mapNotNull { (it as? JsonObject)?.str("targetId") }

    /** targetId → requireApproval, for all targets of the project/region. */
    fun targetApprovals(gcpProject: String, region: String): Map<String, Boolean> =
        getJson("$BASE/projects/$gcpProject/locations/$region/targets?pageSize=1000")
            .arr("targets")
            .mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                val id = obj.str("name")?.substringAfterLast('/') ?: return@mapNotNull null
                id to obj.bool("requireApproval")
            }
            .toMap()

    /**
     * Recent rollouts across all releases, newest first. Pages through the
     * aggregated `releases/-/rollouts` listing, stopping as soon as every
     * target in [targets] has been seen; falls back to per-release listing
     * should the wildcard ever be rejected.
     */
    fun recentRollouts(ref: PipelineRef, targets: Set<String>): List<RolloutInfo> =
        try {
            aggregatedRollouts(ref, targets)
        } catch (_: CloudDeployException) {
            rolloutsPerRelease(ref)
        }

    /**
     * Every existing rollout of one [release] across its targets — the
     * authoritative list (not the bounded per-target snapshot), so callers can
     * allocate a non-colliding next rolloutId even for an old release.
     */
    fun releaseRollouts(ref: PipelineRef, release: String): List<RolloutInfo> =
        parseRollouts(getJson("$BASE/${ref.resourcePath}/releases/$release/rollouts?pageSize=$PAGE_SIZE"))

    /**
     * Promotes [release] to [targetId] by creating a rollout — the REST
     * equivalent of `gcloud deploy releases promote`. Returns when the API
     * accepts the long-running operation; progress shows up on refresh.
     */
    fun promote(ref: PipelineRef, release: String, targetId: String, rolloutId: String) {
        val body = JsonObject().apply { addProperty("targetId", targetId) }
        postJson("$BASE/${ref.resourcePath}/releases/$release/rollouts?rolloutId=${enc(rolloutId)}", body)
    }

    /**
     * Approves ([approved] = true) or rejects (false) a rollout awaiting
     * approval — the REST equivalent of the Cloud Console Approve/Reject
     * buttons. [rolloutName] is the rollout's full resource name (…/rollouts/{ro}).
     * The API returns an empty body; a rollout that is no longer awaiting
     * approval yields FAILED_PRECONDITION, surfaced as a [CloudDeployException].
     */
    fun approveRollout(rolloutName: String, approved: Boolean) {
        val body = JsonObject().apply { addProperty("approved", approved) }
        postJson("$BASE/$rolloutName:approve", body)
    }

    /**
     * Retries the failed [jobId] in [phaseId] of the rollout [rolloutName] — the
     * REST equivalent of the Cloud Console "Retry" button. It resumes the SAME
     * rollout in place (no new rollout is created). The API returns an empty
     * body; a job that is no longer retryable yields FAILED_PRECONDITION (or
     * INVALID_ARGUMENT for a stale phase/job id), surfaced as a [CloudDeployException].
     */
    fun retryJob(rolloutName: String, phaseId: String, jobId: String) {
        val body = JsonObject().apply {
            addProperty("phaseId", phaseId)
            addProperty("jobId", jobId)
        }
        postJson("$BASE/$rolloutName:retryJob", body)
    }

    /**
     * The current credentials' rollout permissions on [ref] — one
     * `testIamPermissions` probe for the approve and retry permissions on the
     * pipeline resource (rollouts have no own IAM policy; they inherit the
     * pipeline's, so this is exact). Approve and reject share one permission.
     * Callers wrap this in runCatching and treat any failure as "unknown".
     */
    fun rolloutPermissions(ref: PipelineRef): RolloutPermissions {
        val body = JsonObject().apply {
            add("permissions", JsonArray().apply { add(APPROVE_PERMISSION); add(RETRY_PERMISSION) })
        }
        val granted = postJson("$BASE/${ref.resourcePath}:testIamPermissions", body)
            .arr("permissions")
            .mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            .toSet()
        return RolloutPermissions(
            canApprove = APPROVE_PERMISSION in granted,
            canRetry = RETRY_PERMISSION in granted,
        )
    }

    /**
     * Full detail of one rollout (phases + their jobs) via a GET on the rollout
     * resource — the source the status panel renders and from which the failed
     * phase/job for [retryJob] is located. [rolloutName] is the full resource name.
     */
    fun rolloutDetail(rolloutName: String): RolloutDetail {
        val obj = getJson("$BASE/$rolloutName")
        return RolloutDetail(
            name = obj.str("name") ?: rolloutName,
            targetId = obj.str("targetId") ?: "",
            state = obj.str("state") ?: "",
            failureReason = obj.str("failureReason") ?: "",
            deployFailureCause = obj.str("deployFailureCause") ?: "",
            createTime = obj.str("createTime") ?: "",
            deployStartTime = obj.str("deployStartTime") ?: "",
            deployEndTime = obj.str("deployEndTime") ?: "",
            phases = obj.arr("phases").mapNotNull { row ->
                val p = row as? JsonObject ?: return@mapNotNull null
                RolloutPhase(
                    id = p.str("id") ?: "",
                    state = p.str("state") ?: "",
                    jobs = parsePhaseJobs(p),
                )
            },
        )
    }

    /**
     * The run of one job, by its JobRun resource name — the failure message and
     * the Cloud Build that ran it (for a logs link). [jobRunName] is `Job.jobRun`.
     */
    fun jobRunDetail(jobRunName: String): JobRunDetail {
        val obj = getJson("$BASE/$jobRunName")
        // Exactly one of the *JobRun oneof branches is set; the failure fields live there.
        val run = JOB_RUN_BRANCHES.firstNotNullOfOrNull { obj.obj(it) }
        return JobRunDetail(
            name = obj.str("name") ?: jobRunName,
            state = obj.str("state") ?: "",
            failureMessage = run.str("failureMessage") ?: "",
            build = run.str("build") ?: "",
        )
    }

    /**
     * The jobs of a phase, in execution order. A normal phase carries a single
     * job per stage under `deploymentJobs`; a canary/multi-target phase carries
     * arrays of child-rollout jobs under `childRolloutJobs`.
     */
    private fun parsePhaseJobs(phase: JsonObject): List<RolloutJob> {
        phase.obj("deploymentJobs")?.let { jobs ->
            return DEPLOYMENT_JOB_FIELDS.mapNotNull { (field, kind) ->
                jobs.obj(field)?.let { parseJob(it, kind) }
            }
        }
        phase.obj("childRolloutJobs")?.let { jobs ->
            return (jobs.arr("createRolloutJobs").map { it to "create child rollout" } +
                jobs.arr("advanceRolloutJobs").map { it to "advance child rollout" })
                .mapNotNull { (row, kind) -> (row as? JsonObject)?.let { parseJob(it, kind) } }
        }
        return emptyList()
    }

    private fun parseJob(job: JsonObject, kind: String): RolloutJob = RolloutJob(
        id = job.str("id") ?: "",
        state = job.str("state") ?: "",
        kind = kind,
        jobRun = job.str("jobRun") ?: "",
    )

    private fun aggregatedRollouts(ref: PipelineRef, targets: Set<String>): List<RolloutInfo> {
        val collected = mutableListOf<RolloutInfo>()
        val seenTargets = mutableSetOf<String>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val url = "$BASE/${ref.resourcePath}/releases/-/rollouts?pageSize=$PAGE_SIZE" +
                (pageToken?.let { "&pageToken=${enc(it)}" } ?: "")
            val response = getJson(url)
            val page = parseRollouts(response)
            collected += page
            seenTargets += page.map { it.targetId }
            pageToken = response.str("nextPageToken")
            if (pageToken == null || seenTargets.containsAll(targets)) return collected
        }
        return collected
    }

    private fun rolloutsPerRelease(ref: PipelineRef): List<RolloutInfo> =
        recentReleases(ref, RELEASE_DEPTH).flatMap { release ->
            parseRollouts(getJson("$BASE/${ref.resourcePath}/releases/${release.release}/rollouts?pageSize=$PAGE_SIZE"))
        }

    /**
     * Recent releases, newest-first (API default; no orderBy — it's rejected).
     * [limit] caps the page size. Used both by the Releases tree node and, with
     * a smaller cap, by the per-release rollout fallback.
     */
    fun recentReleases(ref: PipelineRef, limit: Int = RELEASES_DEPTH): List<ReleaseInfo> =
        getJson("$BASE/${ref.resourcePath}/releases?pageSize=$limit")
            .arr("releases")
            .mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                val name = obj.str("name") ?: return@mapNotNull null
                ReleaseInfo(
                    name = name,
                    createTime = obj.str("createTime") ?: "",
                    abandoned = obj.bool("abandoned"),
                    renderState = obj.str("renderState") ?: "",
                )
            }

    private fun parseRollouts(response: JsonObject): List<RolloutInfo> =
        response.arr("rollouts")
            .mapNotNull { row ->
                val obj = row as? JsonObject ?: return@mapNotNull null
                val name = obj.str("name") ?: return@mapNotNull null
                RolloutInfo(
                    name = name,
                    targetId = obj.str("targetId") ?: return@mapNotNull null,
                    state = obj.str("state") ?: "",
                    approvalState = obj.str("approvalState") ?: "",
                    // .../releases/<release>/rollouts/<rollout>
                    release = name.substringAfter("/releases/").substringBefore('/'),
                    createTime = obj.str("createTime") ?: "",
                    deployEndTime = obj.str("deployEndTime") ?: "",
                )
            }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private fun getJson(url: String): JsonObject = request(url, body = null)

    private fun postJson(url: String, body: JsonObject): JsonObject = request(url, body)

    private fun request(url: String, body: JsonObject?): JsonObject {
        var token = GcloudAuth.token()
        var lastError = "request failed"
        repeat(2) { attempt ->
            val builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $token")
                .timeout(Duration.ofSeconds(30))
            if (body == null) {
                builder.GET()
            } else {
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            }
            val response = try {
                http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw CloudDeployException("network error: ${e.message}")
            }
            when {
                response.statusCode() in 200..299 ->
                    return runCatching { JsonParser.parseString(response.body()).asJsonObject }
                        .getOrElse { JsonObject() }
                // Expired/revoked token: refresh once and retry.
                response.statusCode() == 401 && attempt == 0 -> {
                    GcloudAuth.invalidate()
                    token = GcloudAuth.token()
                }
                else -> lastError = apiError(response)
            }
        }
        throw CloudDeployException(lastError)
    }

    private fun apiError(response: HttpResponse<String>): String {
        val message = runCatching {
            JsonParser.parseString(response.body()).asJsonObject
                .obj("error").str("message")
        }.getOrNull()
        return message ?: "HTTP ${response.statusCode()}"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.arr(key: String): JsonArray = (this?.get(key) as? JsonArray) ?: JsonArray()
    private fun JsonObject?.str(key: String): String? = this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject?.bool(key: String): Boolean =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private const val BASE = "https://clouddeploy.googleapis.com/v1"
    /** IAM permission gating both approve and reject of a rollout. */
    private const val APPROVE_PERMISSION = "clouddeploy.rollouts.approve"
    /** IAM permission gating retry of a rollout's failed job. */
    private const val RETRY_PERMISSION = "clouddeploy.rollouts.retryJob"
    /** `deploymentJobs` stage fields, in execution order, mapped to readable kinds. */
    private val DEPLOYMENT_JOB_FIELDS = listOf(
        "predeployJob" to "predeploy",
        "deployJob" to "deploy",
        "verifyJob" to "verify",
        "analysisJob" to "analysis",
        "postdeployJob" to "postdeploy",
    )
    /** JobRun oneof branches that carry `failureMessage`/`build` (Cloud Build jobs). */
    private val JOB_RUN_BRANCHES = listOf("deployJobRun", "verifyJobRun", "predeployJobRun", "postdeployJobRun")
    /** Releases listed per-release-rollout fallback (kept small to bound rollout fetches). */
    private const val RELEASE_DEPTH = 5
    /** Releases shown in the Releases tree node. */
    private const val RELEASES_DEPTH = 20
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 5
}
