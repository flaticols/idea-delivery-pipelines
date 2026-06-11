package dev.flaticols.deliverypipeline.gcloud

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler

class CloudDeployException(message: String) : RuntimeException(message)

/**
 * The only gcloud CLI touchpoint: `gcloud auth print-access-token`, called at
 * most once per ~50 minutes. All data queries go straight to the REST API with
 * the cached token, so the slow Python CLI startup is paid once per token
 * lifetime instead of per query.
 */
object GcloudAuth {

    /** Access token + wall-clock expiry (tokens live ~60 min; we renew at 50). */
    @Volatile
    private var cached: Pair<String, Long>? = null

    fun token(): String {
        cached?.let { (token, expiresAt) -> if (System.currentTimeMillis() < expiresAt) return token }
        synchronized(this) {
            cached?.let { (token, expiresAt) -> if (System.currentTimeMillis() < expiresAt) return token }
            val token = fetchToken()
            cached = token to System.currentTimeMillis() + TOKEN_TTL_MS
            return token
        }
    }

    /** Drop the cached token (e.g. after an HTTP 401) so the next call re-fetches. */
    fun invalidate() {
        cached = null
    }

    private fun fetchToken(): String {
        val exe = PathEnvironmentVariableUtil.findInPath("gcloud")?.absolutePath ?: "gcloud"
        val cmd = GeneralCommandLine(exe, "auth", "print-access-token")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val output = try {
            CapturingProcessHandler(cmd).runProcess(TIMEOUT_MS)
        } catch (e: Exception) {
            throw CloudDeployException("cannot run gcloud: ${e.message}")
        }
        if (output.isTimeout) throw CloudDeployException("gcloud auth print-access-token timed out")
        if (output.exitCode != 0) {
            val firstErr = output.stderr.lineSequence().firstOrNull { it.isNotBlank() }
            throw CloudDeployException(firstErr ?: "gcloud auth failed (${output.exitCode}) — run `gcloud auth login`")
        }
        return output.stdout.trim().ifEmpty { throw CloudDeployException("gcloud returned an empty token") }
    }

    private const val TOKEN_TTL_MS = 50L * 60 * 1000
    private const val TIMEOUT_MS = 30_000
}
