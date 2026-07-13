package com.makia.hedgehogsms.llm

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientSecurityTest {
    @Test fun `client sends only approved redacted payload to fixed https endpoint`() = runBlocking {
        val vault = FakeVault()
        val handle = vault.store("llm.invalid", "fictional-secret".toCharArray())
        var request: HttpsRequest? = null
        val client = OpenAiCompatibleClient(
            ProviderConfig("https://llm.invalid/v1", "fictional-model"), vault,
            HttpsTransport { request = it; HttpsResponse(200, "opaque response".toByteArray()) },
            AnnotationResponseParser { _, allowed -> listOf(Annotation(allowed.single(), LabelSuggestion.Unknown, 0.4)) },
        )
        val result = client.suggest(
            ApprovedRedactedBatch("batch-x", "llm.invalid", "fictional-model", listOf(RedactedSample("sample-x", "【虚构甲】验证码 <code>"))),
            handle,
        )
        val sent = requireNotNull(request)
        val body = sent.utf8Body.toString(Charsets.UTF_8)
        assertEquals(URI("https://llm.invalid/v1/chat/completions"), sent.uri)
        assertTrue(sent.headers.getValue("authorization").startsWith("Bearer "))
        assertTrue(body.contains("sample-x") && body.contains("<code>"))
        assertFalse(body.contains("fictional-secret"))
        assertEquals(LabelSuggestion.Unknown, result.single().suggestion)
    }

    @Test fun `client rejects provider mismatch and unredacted payload before transport`() = runBlocking {
        val vault = FakeVault()
        val handle = vault.store("llm.invalid", "fictional-secret".toCharArray())
        var calls = 0
        val client = OpenAiCompatibleClient(
            ProviderConfig("https://llm.invalid", "m"), vault,
            HttpsTransport { calls++; HttpsResponse(200, byteArrayOf()) },
            AnnotationResponseParser { _, _ -> emptyList() },
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.suggest(ApprovedRedactedBatch("b", "other.invalid", "m", emptyList()), handle) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.suggest(ApprovedRedactedBatch("b", "llm.invalid", "m", listOf(RedactedSample("s", "虚构验证码 123456"))), handle) }
        }
        assertEquals(0, calls)
    }

    private class FakeVault : ApiKeyVault {
        private val values = mutableMapOf<String, CharArray>()
        override fun store(providerHost: String, secret: CharArray): ApiKeyHandle = ApiKeyHandle("key-$providerHost").also { values[it.opaqueId] = secret.copyOf() }
        override suspend fun <T> withSecret(handle: ApiKeyHandle, expectedProviderHost: String, block: suspend (CharArray) -> T): T {
            require(handle.opaqueId == "key-$expectedProviderHost")
            return block(values.getValue(handle.opaqueId).copyOf())
        }
        override fun delete(handle: ApiKeyHandle) { values.remove(handle.opaqueId)?.fill('\u0000') }
        override fun clearAll() { values.values.forEach { it.fill('\u0000') }; values.clear() }
    }
}
