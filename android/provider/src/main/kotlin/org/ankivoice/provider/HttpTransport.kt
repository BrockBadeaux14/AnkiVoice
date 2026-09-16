package org.ankivoice.provider

import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal sealed interface HttpResult {
    data class Response(val status: Int, val body: String) : HttpResult

    data object Timeout : HttpResult

    /** Not HTTPS, a redirect, a TLS problem or any other transport fault. */
    data class Refused(val reason: String) : HttpResult
}

/**
 * The app's only network seam. `:provider` is the one module that declares
 * `android.permission.INTERNET`, and this is the one type in it that opens a connection.
 */
internal interface HttpTransport {
    fun get(url: String, key: String, timeoutMs: Int): HttpResult

    fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult
}

/**
 * HTTPS only, and never follows a redirect: a 3xx is a refusal, so the pinned endpoint
 * cannot be moved by a reply. The key is sent in the Authorization header and nowhere
 * else — never in the URL, and never logged.
 */
internal class HttpsUrlTransport : HttpTransport {
    override fun get(url: String, key: String, timeoutMs: Int): HttpResult = call(url, key, null, timeoutMs)

    override fun post(url: String, key: String, body: String, timeoutMs: Int): HttpResult =
        call(url, key, body, timeoutMs)

    private fun call(url: String, key: String, body: String?, timeoutMs: Int): HttpResult {
        if (!url.startsWith("https://")) return HttpResult.Refused("not an HTTPS URL")
        var connection: HttpsURLConnection? = null
        return try {
            val opened = URL(url).openConnection()
            connection = opened as? HttpsURLConnection ?: return HttpResult.Refused("not an HTTPS connection")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status in 300..399) return HttpResult.Refused("refusing a redirect from the pinned endpoint")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResult.Response(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } catch (_: InterruptedIOException) {
            HttpResult.Timeout
        } catch (error: IOException) {
            // The message can quote the URL but never the key, which is only a header.
            HttpResult.Refused(error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            HttpResult.Refused(error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
