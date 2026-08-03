package dev.zephbyte.premiere.upload

import dev.zephbyte.premiere.PremiereConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The mod's entire R2 surface: presigned PUTs for the upload page, presigned
 * GETs minted at play time, and bucket listing for the movie library.
 *
 * The bucket stays fully private — no public access, no r2.dev subdomain.
 * Nothing is reachable without a signature, playback links expire on their
 * own (so a leaked link dies within hours), and the credential never leaves
 * this class except as a signature.
 */
object R2Storage {

    /** Playback links outlive the longest movie night, then self-destruct. */
    const val PLAYBACK_EXPIRY_S = 12 * 3600L

    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private val AMZ_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    private fun host() = "${PremiereConfig.r2AccountId}.r2.cloudflarestorage.com"

    fun presignPut(key: String, expiresSeconds: Long): String = presign("PUT", key, expiresSeconds)

    fun presignGet(key: String, expiresSeconds: Long = PLAYBACK_EXPIRY_S): String =
        presign("GET", key, expiresSeconds)

    data class R2Object(val key: String, val size: Long, val lastModified: String)

    /** Lists bucket contents (first 1000; a movie bucket stays tiny). */
    fun listObjects(): List<R2Object> {
        val response = sendSigned("GET", null, "list-type=2")
        if (response.statusCode() != 200) {
            throw IllegalStateException("R2 list failed: HTTP ${response.statusCode()}: ${response.body().take(200)}")
        }
        return Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)
            .findAll(response.body())
            .mapNotNull { entry ->
                val block = entry.groupValues[1]
                val key = Regex("<Key>(.*?)</Key>").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                R2Object(
                    key = unescapeXml(key),
                    size = Regex("<Size>(\\d+)</Size>").find(block)?.groupValues?.get(1)?.toLong() ?: 0,
                    lastModified = Regex("<LastModified>(.*?)</LastModified>").find(block)?.groupValues?.get(1) ?: "",
                )
            }
            .toList()
    }

    fun listKeys(): List<String> = listObjects().map { it.key }

    fun deleteObject(key: String) {
        val response = sendSigned("DELETE", key, "")
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("R2 delete failed: HTTP ${response.statusCode()}: ${response.body().take(200)}")
        }
    }

    /** S3 has no rename; this is the copy half (server-side, no data transfer). */
    fun copyObject(sourceKey: String, destinationKey: String) {
        val copySource = "/${PremiereConfig.r2Bucket}/${encodeKey(sourceKey)}"
        val response = sendSigned(
            "PUT",
            destinationKey,
            "",
            mapOf(
                "x-amz-copy-source" to copySource,
                // R2-specific destination condition: never overwrite an object
                // that appeared between the dashboard's list and copy calls.
                "cf-copy-destination-if-none-match" to "*",
            ),
        )
        if (response.statusCode() !in 200..299 || response.body().contains("<Error>")) {
            throw IllegalStateException("R2 copy failed: HTTP ${response.statusCode()}: ${response.body().take(200)}")
        }
    }

    /**
     * Copies, verifies, and only then deletes the source. R2's S3 API is
     * strongly consistent, so a successful copy must immediately appear in
     * the following listing with the same byte count.
     */
    fun renameObject(sourceKey: String, destinationKey: String, expectedSize: Long) {
        copyObject(sourceKey, destinationKey)
        val copied = try {
            listObjects().firstOrNull { it.key == destinationKey }
        } catch (e: Exception) {
            runCatching { deleteObject(destinationKey) }
            throw IllegalStateException("Could not verify the R2 copy; the original was kept: ${e.message}", e)
        }
        if (copied == null || copied.size != expectedSize) {
            runCatching { deleteObject(destinationKey) }
            val detail = if (copied == null) "the copied object was not found" else
                "expected $expectedSize bytes but copied ${copied.size}"
            throw IllegalStateException("R2 copy verification failed: $detail; the original was kept")
        }
        try {
            deleteObject(sourceKey)
        } catch (e: Exception) {
            throw IllegalStateException(
                "R2 copied the movie to '$destinationKey', but could not remove '$sourceKey': ${e.message}",
                e,
            )
        }
    }

    /** Header-signed (SigV4) request to the bucket or an object in it. */
    private fun sendSigned(
        method: String,
        key: String?,
        query: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val host = host()
        val amzDate = AMZ_DATE.format(Instant.now())
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/auto/s3/aws4_request"
        val path = "/${PremiereConfig.r2Bucket}" + if (key != null) "/${encodeKey(key)}" else ""

        // Canonical headers must be sorted by (lowercase) name.
        val headers = sortedMapOf(
            "host" to host,
            "x-amz-content-sha256" to EMPTY_SHA256,
            "x-amz-date" to amzDate,
        )
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v }
        val canonicalHeaders = headers.entries.joinToString("") { (k, v) -> "$k:$v\n" }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalRequest = listOf(
            method, path, query, canonicalHeaders, signedHeaders, EMPTY_SHA256,
        ).joinToString("\n")
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest.toByteArray())),
        ).joinToString("\n")
        val signature = hex(hmac(signingKey(dateStamp), stringToSign))

        val uri = URI("https://$host$path" + if (query.isNotEmpty()) "?$query" else "")
        val builder = HttpRequest.newBuilder(uri)
        headers.forEach { (k, v) -> if (k != "host") builder.header(k, v) }
        val request = builder
            .header(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=${PremiereConfig.r2AccessKeyId}/$scope, " +
                    "SignedHeaders=$signedHeaders, Signature=$signature"
            )
            .timeout(Duration.ofSeconds(15))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun presign(method: String, key: String, expiresSeconds: Long, now: Instant = Instant.now()): String {
        val host = host()
        val path = "/${PremiereConfig.r2Bucket}/${encodeKey(key)}"
        val amzDate = AMZ_DATE.format(now)
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/auto/s3/aws4_request"

        val query = listOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to "${PremiereConfig.r2AccessKeyId}/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        ).joinToString("&") { (k, v) -> "$k=${rfc3986(v)}" }

        val canonicalRequest = listOf(
            method, path, query, "host:$host\n", "host", "UNSIGNED-PAYLOAD",
        ).joinToString("\n")
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest.toByteArray())),
        ).joinToString("\n")
        val signature = hex(hmac(signingKey(dateStamp), stringToSign))

        return "https://$host$path?$query&X-Amz-Signature=$signature"
    }

    private fun signingKey(dateStamp: String): ByteArray {
        var key = hmac("AWS4${PremiereConfig.r2SecretAccessKey}".toByteArray(), dateStamp)
        key = hmac(key, "auto")
        key = hmac(key, "s3")
        return hmac(key, "aws4_request")
    }

    /** Keep names URL- and chat-command-friendly. */
    fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return cleaned.ifEmpty { "movie" }
    }

    private fun encodeKey(key: String): String =
        key.split('/').joinToString("/") { rfc3986(it) }

    /** S3 canonical encoding: like URL encoding but %20 for space and no '+'. */
    private fun rfc3986(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(text.toByteArray())
        }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
