package com.denis.georgiatransit.android

import android.content.Context
import com.chuckerteam.chucker.api.BodyDecoder
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.chuckerteam.chucker.api.RetentionManager
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

/** Debug-only Chucker adapter that never gives its persistence layer credential values. */
internal class RedactingChuckerInterceptor(context: Context) : Interceptor {
    private val chucker =
        ChuckerInterceptor.Builder(context.applicationContext)
            .collector(
                ChuckerCollector(
                    context = context.applicationContext,
                    showNotification = false,
                    retentionPeriod = RetentionManager.Period.ONE_HOUR,
                ),
            )
            .maxContentLength(MAX_CAPTURED_BODY_BYTES)
            .redactHeaders(*REDACTED_HEADERS)
            .addBodyDecoder(RedactingBodyDecoder)
            .alwaysReadResponseBody(true)
            .createShortcut(false)
            .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val redactedRequest = originalRequest.toInspectionRequest()
        val redactedChain =
            RedactedRequestChain(
                delegate = chain,
                requestForInspection = redactedRequest,
                requestForNetwork = originalRequest,
        )

        return try {
            val inspectionResponse = chucker.intercept(redactedChain)
            inspectionResponse.close()
            redactedChain.originalNetworkResponse
                ?: error("Chucker completed without an original network response")
        } catch (inspectionFailure: IOException) {
            redactedChain.originalNetworkFailure?.let { throw it }
            redactedChain.originalNetworkResponse ?: throw inspectionFailure
        }
    }

    private class RedactedRequestChain(
        private val delegate: Interceptor.Chain,
        private val requestForInspection: Request,
        private val requestForNetwork: Request,
    ) : Interceptor.Chain by delegate {
        var originalNetworkFailure: IOException? = null
            private set
        var originalNetworkResponse: Response? = null
            private set

        override fun request(): Request = requestForInspection

        override fun proceed(request: Request): Response =
            try {
                val originalResponse = delegate.proceed(requestForNetwork)
                originalNetworkResponse = originalResponse
                originalResponse
                    .newBuilder()
                    .request(requestForInspection)
                    .headers(RedactingChuckerInterceptor.redactInspectionHeaders(originalResponse.headers))
                    .message(RedactingBodyDecoder.redactText(originalResponse.message))
                    .body(RedactingChuckerInterceptor.inspectionResponseBody(originalResponse))
                    .build()
            } catch (networkFailure: IOException) {
                originalNetworkFailure = networkFailure
                throw SanitizedInspectionIOException(RedactingBodyDecoder.redactText(networkFailure.toString()))
            }
    }

    private fun Request.toInspectionRequest(): Request =
        newBuilder()
            .url(redactCredentialUrl(url))
            .headers(redactInspectionHeaders(headers))
            .method(method, body?.toInspectionPreviewBody())
            .build()

    private fun RequestBody.toInspectionPreviewBody(): RequestBody {
        if (isOneShot() || isDuplex()) return omittedPreviewBody(contentType())

        val contentLength =
            try {
                contentLength()
            } catch (_: IOException) {
                -1L
            }
        if (contentLength !in 0..MAX_CAPTURED_BODY_BYTES) return omittedPreviewBody(contentType())

        val preview =
            try {
                val buffer = Buffer()
                val limitedSink =
                    object : ForwardingSink(buffer) {
                        private var bytesWritten = 0L

                        override fun write(
                            source: Buffer,
                            byteCount: Long,
                        ) {
                            if (bytesWritten + byteCount > MAX_CAPTURED_BODY_BYTES) {
                                throw InspectionBodyLimitExceeded()
                            }
                            super.write(source, byteCount)
                            bytesWritten += byteCount
                        }
                    }.buffer()
                writeTo(limitedSink)
                limitedSink.flush()
                RedactingBodyDecoder.inspectionPreview(buffer.readByteString(), contentType())
            } catch (_: IOException) {
                OMITTED_REQUEST_BODY_MARKER
            }
        return preview.toBoundedInspectionRequestBody(contentType())
    }

    private fun String.toBoundedInspectionRequestBody(contentType: MediaType?): RequestBody =
        if (toByteArray(Charsets.UTF_8).size <= MAX_CAPTURED_BODY_BYTES) {
            toRequestBody(contentType)
        } else {
            omittedPreviewBody(contentType)
        }

    private fun omittedPreviewBody(contentType: MediaType?): RequestBody =
        OMITTED_REQUEST_BODY_MARKER.toRequestBody(contentType)

    private class InspectionBodyLimitExceeded : IOException()

    private class SanitizedInspectionIOException(
        message: String,
    ) : IOException(message)

    private companion object {
        const val MAX_CAPTURED_BODY_BYTES = 256L * 1024L
        const val OMITTED_REQUEST_BODY_MARKER = "[Request body omitted before inspection]"
        const val OMITTED_RESPONSE_BODY_MARKER =
            "[Response body omitted before inspection: unsupported or larger than 256 KiB]"

        val inspectionMarkerMediaType = "text/plain; charset=utf-8".toMediaType()

        val REDACTED_HEADERS =
            arrayOf(
                "Authorization",
                "Proxy-Authorization",
                "X-Api-Key",
                "Api-Key",
                "Cookie",
                "Set-Cookie",
                "X-Auth-Token",
                "X-Access-Token",
                "X-Api-Token",
                "X-Session-Token",
                "X-Secret",
                "X-Password",
                "X-Credential",
                "X-Access-Key",
                "X-Bearer-Token",
                "Token",
                "Secret",
                "Password",
                "Credential",
                "Session",
                "WWW-Authenticate",
                "Location",
                "Content-Location",
                "Referer",
            )

        fun redactInspectionHeaders(headers: okhttp3.Headers): okhttp3.Headers {
            val source = headers
            return headers.newBuilder().apply {
                for (index in 0 until source.size) {
                    val name = source.name(index)
                    if (name.isCredentialHeaderName()) set(name, REDACTED_VALUE)
                }
            }.build()
        }

        fun inspectionResponseBody(response: Response): ResponseBody {
            val originalBody = response.body
            val contentType = originalBody.contentType()
            if (!RedactingBodyDecoder.supportsInspectionPreview(contentType)) {
                return omittedInspectionResponseBody()
            }
            if (originalBody.contentLength() > MAX_CAPTURED_BODY_BYTES) {
                return omittedInspectionResponseBody()
            }

            val preview =
                try {
                    RedactingBodyDecoder.inspectionPreview(
                        body = response.peekBody(MAX_CAPTURED_BODY_BYTES).bytes().toByteString(),
                        contentType = contentType,
                    )
                } catch (_: Exception) {
                    return omittedInspectionResponseBody()
                }
            return preview.toBoundedInspectionResponseBody(contentType)
        }

        private fun omittedInspectionResponseBody(): ResponseBody =
            OMITTED_RESPONSE_BODY_MARKER.toResponseBody(inspectionMarkerMediaType)

        private fun String.toBoundedInspectionResponseBody(contentType: MediaType?): ResponseBody {
            if (toByteArray(Charsets.UTF_8).size > MAX_CAPTURED_BODY_BYTES) {
                return omittedInspectionResponseBody()
            }
            return toResponseBody(contentType)
        }

        fun redactCredentialUrl(url: HttpUrl): HttpUrl =
            url.newBuilder().apply {
                username("")
                password("")
                for (index in 0 until url.querySize) {
                    val name = url.queryParameterName(index)
                    if (name.isCredentialQueryName()) setQueryParameter(name, REDACTED_VALUE)
                }
            }.build()

        fun String.isCredentialQueryName(): Boolean {
            val normalized = lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "")
            return normalized in EXACT_CREDENTIAL_NAMES || CREDENTIAL_NAME_FRAGMENT.containsMatchIn(normalized)
        }

        fun String.isCredentialHeaderName(): Boolean {
            val normalized = lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "")
            return normalized in INSPECTION_REDACTED_HEADER_NAMES ||
                CREDENTIAL_NAME_FRAGMENT.containsMatchIn(normalized)
        }

        const val REDACTED_VALUE = "[REDACTED]"
        val EXACT_CREDENTIAL_NAMES =
            setOf(
                "authorization",
                "proxyauthorization",
                "xapikey",
                "apikey",
                "cookie",
                "setcookie",
                "xauthtoken",
                "xaccesstoken",
                "accesstoken",
                "refreshtoken",
                "idtoken",
            )
        val CREDENTIAL_NAME_FRAGMENT = Regex("token|secret|password|credential|apikey|auth|session")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]")
        val INSPECTION_REDACTED_HEADER_NAMES = REDACTED_HEADERS.map { name ->
            name.lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "")
        }.toSet()
    }
}

private object RedactingBodyDecoder : BodyDecoder {
    fun inspectionPreview(
        body: ByteString,
        contentType: MediaType?,
    ): String = redactBody(body, contentType)

    fun supportsInspectionPreview(contentType: MediaType?): Boolean =
        contentType?.toString()?.lowercase(Locale.ROOT).orEmpty().isSupportedText()

    override fun decodeRequest(
        request: Request,
        body: ByteString,
    ): String = redactBody(body, request.body?.contentType())

    override fun decodeResponse(
        response: Response,
        body: ByteString,
    ): String = redactBody(body, response.body.contentType())

    private fun redactBody(
        body: ByteString,
        contentType: MediaType?,
    ): String {
        val mediaType = contentType?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (!mediaType.isSupportedText()) return "[Binary or unsupported body omitted]"

        val text = body.string(contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        return when {
            mediaType.isJson() -> redactJson(text)
            mediaType.contains("x-www-form-urlencoded") -> redactForm(text)
            else -> redactText(text)
        }
    }

    private fun redactJson(text: String): String =
        try {
            when (text.trimStart().firstOrNull()) {
                '{' -> redactJsonObject(JSONObject(text)).toString()
                '[' -> redactJsonArray(JSONArray(text)).toString()
                else -> redactText(text)
            }
        } catch (_: Exception) {
            redactText(text)
        }

    private fun redactJsonObject(source: JSONObject): JSONObject =
        JSONObject().also { target ->
            source.keys().forEach { key ->
                target.put(
                    key,
                    if (key.isCredentialName()) REDACTED_VALUE else redactJsonValue(source.opt(key)),
                )
            }
        }

    private fun redactJsonArray(source: JSONArray): JSONArray =
        JSONArray().also { target ->
            for (index in 0 until source.length()) {
                target.put(redactJsonValue(source.opt(index)))
            }
        }

    private fun redactJsonValue(value: Any?): Any? =
        when (value) {
            is JSONObject -> redactJsonObject(value)
            is JSONArray -> redactJsonArray(value)
            else -> value
        }

    private fun redactForm(text: String): String =
        FORM_FIELD.replace(text) { match ->
            val rawName = match.groupValues[2]
            val decodedName = runCatching { URLDecoder.decode(rawName, Charsets.UTF_8.name()) }.getOrDefault(rawName)
            if (decodedName.isCredentialName()) {
                "${match.groupValues[1]}$rawName=$REDACTED_VALUE"
            } else {
                match.value
            }
        }

    fun redactText(text: String): String =
        text
            .replace(CREDENTIAL_URL_USER_INFO) { match ->
                "${match.groupValues[1]}$REDACTED_VALUE@"
            }
            .replace(CREDENTIAL_QUERY_VALUE) { match ->
                "${match.groupValues[1]}$REDACTED_VALUE"
            }.replace(CREDENTIAL_KEY_VALUE) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$REDACTED_VALUE"
            }

    private fun String.isCredentialName(): Boolean {
        val normalized = lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "")
        return normalized in EXACT_CREDENTIAL_NAMES || CREDENTIAL_NAME_FRAGMENT.containsMatchIn(normalized)
    }

    private fun String.isSupportedText(): Boolean =
        !contains("xml") && (startsWith("text/") || isJson() || contains("x-www-form-urlencoded"))

    private fun String.isJson(): Boolean = contains("json")

    private const val REDACTED_VALUE = "[REDACTED]"
    private val EXACT_CREDENTIAL_NAMES =
        setOf(
            "authorization",
            "proxyauthorization",
            "xapikey",
            "apikey",
            "cookie",
            "setcookie",
            "xauthtoken",
            "xaccesstoken",
            "accesstoken",
            "refreshtoken",
            "idtoken",
        )
    private val CREDENTIAL_NAME_FRAGMENT = Regex("token|secret|password|credential|apikey|auth|session")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]")
    private val FORM_FIELD = Regex("(?i)(^|[&;])([^=&;]+)=([^&;]*)")
    private val CREDENTIAL_QUERY_VALUE =
        Regex("(?i)([?&](?:[^=&?#]*?(?:token|secret|password|credential|api[_-]?key|auth|session)[^=&?#]*)=)[^&#\\s]+")
    private val CREDENTIAL_URL_USER_INFO = Regex("(?i)(https?://)(?:[^/@\\s]+@)")
    private val CREDENTIAL_KEY_VALUE =
        Regex(
            "(?i)\\b(authorization|proxy-authorization|x-api-key|api-key|cookie|set-cookie|x-auth-token|x-access-token|access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|password|secret|credential|session(?:[_-]?id)?)(\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|[^,\\s&;}\\]]+)",
        )
}
