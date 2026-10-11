package com.map.update

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubUpdateCheckerTest {

    @Test
    fun check_shouldReturnUpdateAvailable_andCacheMetadata() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val store = InMemoryUpdateSettingsStore()
        var fetchCalls = 0
        val json = buildSignedUpdateJson(
            keyPair = keyPair,
            versionCode = 17,
            versionName = "0.2.0"
        )
        val checker = GitHubUpdateChecker(
            settings = store,
            metadataUrl = "https://example.com/update.json",
            signingPublicKeyDerBase64 = publicKeyBase64,
            currentVersionCode = 16,
            sdkInt = 34,
            nowProvider = { 1_000L },
            sleepProvider = {},
            fetchProvider = {
                fetchCalls += 1
                json
            }
        )

        val result = checker.check()

        if (result !is UpdateCheckResult.UpdateAvailable) {
            fail("Expected UpdateAvailable, got $result")
        }
        assertEquals(1, fetchCalls)
        assertEquals(json, store.cachedJson)
        assertEquals(1_000L, store.cachedTs)
    }

    @Test
    fun check_shouldUseFreshCache_withoutNetworkCall() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val store = InMemoryUpdateSettingsStore()
        val json = buildSignedUpdateJson(
            keyPair = keyPair,
            versionCode = 17,
            versionName = "0.2.0"
        )
        store.cachedJson = json
        store.cachedTs = 10_000L
        var fetchCalls = 0
        val checker = GitHubUpdateChecker(
            settings = store,
            metadataUrl = "https://example.com/update.json",
            signingPublicKeyDerBase64 = publicKeyBase64,
            currentVersionCode = 16,
            sdkInt = 34,
            nowProvider = { 10_001L },
            sleepProvider = {},
            fetchProvider = {
                fetchCalls += 1
                json
            }
        )

        val result = checker.check()

        if (result !is UpdateCheckResult.UpdateAvailable) {
            fail("Expected UpdateAvailable from cache, got $result")
        }
        assertEquals(0, fetchCalls)
    }

    @Test
    fun check_shouldFail_onInvalidSignature() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val wrongKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(wrongKeyPair.public.encoded)
        val store = InMemoryUpdateSettingsStore()
        val json = buildSignedUpdateJson(
            keyPair = keyPair,
            versionCode = 17,
            versionName = "0.2.0"
        )
        val checker = GitHubUpdateChecker(
            settings = store,
            metadataUrl = "https://example.com/update.json",
            signingPublicKeyDerBase64 = publicKeyBase64,
            currentVersionCode = 16,
            sdkInt = 34,
            nowProvider = { 1_000L },
            sleepProvider = {},
            fetchProvider = { json }
        )

        val result = checker.check()

        if (result !is UpdateCheckResult.Failed) {
            fail("Expected Failed, got $result")
        }
        val failed = result as UpdateCheckResult.Failed
        assertEquals(UpdateCheckErrorCode.INVALID_SIGNATURE, failed.code)
    }

    private fun buildSignedUpdateJson(
        keyPair: java.security.KeyPair,
        versionCode: Int,
        versionName: String
    ): String {
        val payload = listOf(
            "versionCode=$versionCode",
            "versionName=$versionName",
            "apkUrl=https://example.com/map-release.apk",
            "sha256=abcdef0123456789",
            "notesRu=RU notes",
            "notesEn=EN notes",
            "publishedAt=2026-04-22T12:00:00Z",
            "minSupportedVersionCode=1",
            "minSdk=21"
        ).joinToString("\n")

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val signatureBase64 = Base64.getEncoder().encodeToString(signer.sign())

        return """
            {
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "apkUrl": "https://example.com/map-release.apk",
              "sha256": "abcdef0123456789",
              "signature": "$signatureBase64",
              "notesRu": "RU notes",
              "notesEn": "EN notes",
              "publishedAt": "2026-04-22T12:00:00Z",
              "minSupportedVersionCode": 1,
              "minSdk": 21
            }
        """.trimIndent()
    }

    private class InMemoryUpdateSettingsStore : UpdateSettingsStore {
        var skippedVersionCode: Int = -1
        var cachedJson: String = ""
        var cachedTs: Long = 0L

        override fun getSkippedUpdateVersionCode(): Int = skippedVersionCode
        override fun setSkippedUpdateVersionCode(versionCode: Int) {
            skippedVersionCode = versionCode
        }

        override fun getCachedUpdateMetadataJson(): String = cachedJson
        override fun setCachedUpdateMetadataJson(json: String) {
            cachedJson = json
        }

        override fun getCachedUpdateMetadataTsMs(): Long = cachedTs
        override fun setCachedUpdateMetadataTsMs(tsMs: Long) {
            cachedTs = tsMs
        }
    }
}
