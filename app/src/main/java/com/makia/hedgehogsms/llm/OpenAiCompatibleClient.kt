package com.makia.hedgehogsms.llm

import java.net.URI

data class HttpsRequest(val uri: URI, val headers: Map<String, String>, val utf8Body: ByteArray) {
    init { require(uri.scheme.equals("https", true)) { "HTTPS is mandatory" } }
}
data class HttpsResponse(val statusCode: Int, val utf8Body: ByteArray)
fun interface HttpsTransport { suspend fun execute(request: HttpsRequest): HttpsResponse }
fun interface AnnotationResponseParser { fun parse(body: ByteArray, allowedSampleIds: Set<String>): List<Annotation> }

class OpenAiCompatibleClient(
    private val config: ProviderConfig,
    private val vault: ApiKeyVault,
    private val transport: HttpsTransport,
    private val parser: AnnotationResponseParser,
) {
    private val endpoint = URI(config.baseUrl.trimEnd('/') + "/chat/completions").also {
        require(it.scheme.equals("https", true) && it.host == config.host)
    }

    suspend fun suggest(batch: ApprovedRedactedBatch, apiKeyHandle: ApiKeyHandle): List<Annotation> {
        require(batch.providerHost == config.host && batch.model == config.model) { "approved batch does not match provider" }
        batch.samples.forEach { RedactedSampleDraft(it.text) }
        val body = encode(batch)
        val response = vault.withSecret(apiKeyHandle, config.host) { secret ->
            val authorization = "Bearer ${secret.concatToString()}"
            transport.execute(HttpsRequest(endpoint, mapOf("authorization" to authorization, "content-type" to "application/json"), body))
        }
        if (response.statusCode !in 200..299) throw HarnessDenied("provider request failed")
        return parser.parse(response.utf8Body, batch.samples.map { it.opaqueId }.toSet())
    }

    private fun encode(batch: ApprovedRedactedBatch): ByteArray {
        val samples = batch.samples.joinToString(",") { sample ->
            "{\"sample_id\":\"${escape(sample.opaqueId)}\",\"text\":\"${escape(sample.text)}\"}"
        }
        return "{\"model\":\"${escape(batch.model)}\",\"messages\":[{\"role\":\"user\",\"content\":\"Classify only these approved redacted samples\"}],\"samples\":[$samples]}"
            .toByteArray(Charsets.UTF_8)
    }

    private fun escape(value: String) = buildString(value.length) {
        value.forEach { char -> when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        } }
    }
}
