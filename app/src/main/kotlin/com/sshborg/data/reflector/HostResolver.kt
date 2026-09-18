package com.sshborg.data.reflector

import com.sshborg.data.db.HostEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves hosts whose address is handed out by a reflector Worker (which also boots the
 * instance if it is stopped) after the user proves who they are with Google.
 */
class HostResolver(private val auth: GoogleAuth) {

    /**
     * Returns [host] unchanged unless it has a [HostEntity.reflectorUrl]; otherwise a copy
     * addressed at the current IP. The stored known_hosts line is re-keyed to that IP, so the
     * pinned server key is still enforced even though the address changes on every boot.
     */
    suspend fun resolve(host: HostEntity): HostEntity {
        val url = host.reflectorUrl?.takeIf { it.isNotBlank() } ?: return host
        // Host configs can be imported from a backup file; never send the Google token over cleartext.
        if (!url.startsWith("https://")) throw ReflectorException("Reflector URL must use https")
        val ip = fetchIp(url, auth.idToken())
        return host.copy(
            hostname = ip,
            knownHostsEntry = host.knownHostsEntry?.let { rekeyKnownHosts(it, ip, host.port) },
        )
    }

    /** [resolve] for UI callers: reports failure as a message via [onError] and returns null. */
    suspend fun resolveOrReport(host: HostEntity, onError: (String) -> Unit): HostEntity? = try {
        resolve(host)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e.message ?: "Could not reach reflector")
        null
    }

    private suspend fun fetchIp(url: String, idToken: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            // The Worker waits up to two minutes for a stopped instance to boot.
            conn.readTimeout = 150_000
            conn.setRequestProperty("Authorization", "Bearer $idToken")
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!ok) {
                throw ReflectorException(
                    when (conn.responseCode) {
                        401 -> "Reflector rejected this Google account"
                        else -> json?.optString("error")?.takeIf { it.isNotEmpty() }
                            ?: "Reflector error (${conn.responseCode})"
                    },
                )
            }
            val ip = json?.optString("ip").orEmpty()
            if (!IPV4.matches(ip)) throw ReflectorException("Reflector returned no usable address")
            ip
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        val IPV4 = Regex("""(\d{1,3}\.){3}\d{1,3}""")
    }
}

class ReflectorException(message: String) : Exception(message)

/** Rewrites the host marker (first token) of a known_hosts line to [ip], in JSch's port syntax. */
internal fun rekeyKnownHosts(entry: String, ip: String, port: Int): String {
    val marker = if (port == 22) ip else "[$ip]:$port"
    return entry.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else marker + line.substring(line.indexOf(' ').takeIf { it >= 0 } ?: line.length)
    }
}
