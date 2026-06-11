package dev.flaticols.deliverypipeline.gcloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.flaticols.deliverypipeline.model.PipelineRef
import dev.flaticols.deliverypipeline.model.RolloutInfo
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
        } catch (e: CloudDeployException) {
            rolloutsPerRelease(ref)
        }

    /**
     * Promotes [release] to [targetId] by creating a rollout — the REST
     * equivalent of `gcloud deploy releases promote`. Returns when the API
     * accepts the long-running operation; progress shows up on refresh.
     */
    fun promote(ref: PipelineRef, release: String, targetId: String, rolloutId: String) {
        val body = JsonObject().apply { addProperty("targetId", targetId) }
        postJson("$BASE/${ref.resourcePath}/releases/$release/rollouts?rolloutId=${enc(rolloutId)}", body)
    }

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
        recentReleases(ref).flatMap { release ->
            parseRollouts(getJson("$BASE/${ref.resourcePath}/releases/$release/rollouts?pageSize=$PAGE_SIZE"))
        }

    /** Newest-first by API default; no orderBy — the API rejects it. */
    private fun recentReleases(ref: PipelineRef): List<String> =
        getJson("$BASE/${ref.resourcePath}/releases?pageSize=$RELEASE_DEPTH")
            .arr("releases")
            .mapNotNull { (it as? JsonObject)?.str("name")?.substringAfterLast('/') }

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

    // ---- plumbing -----------------------------------------------------------

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
    private const val RELEASE_DEPTH = 5
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 5
}
