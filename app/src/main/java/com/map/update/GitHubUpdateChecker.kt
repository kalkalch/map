package com.map.update

import android.os.Build
import android.util.Log
import com.map.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

class GitHubUpdateChecker(
    private val settings: UpdateSettingsStore,
    private val metadataUrl: String = BuildConfig.UPDATE_METADATA_URL,
    private val signingPublicKeyDerBase64: String = BuildConfig.UPDATE_SIGNING_PUBLIC_KEY_DER_BASE64,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val sleepProvider: (Long) -> Unit = { Thread.sleep(it) },
    private val fetchProvider: (() -> String?)? = null
) {
    companion object {
        private const val TAG = "GitHubUpdateChecker"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 700L
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    }

    fun check(): UpdateCheckResult {
        val now = nowProvider()
        val cacheAgeMs = now - settings.getCachedUpdateMetadataTsMs()
        val cachedJson = settings.getCachedUpdateMetadataJson()
        if (cachedJson.isNotBlank() && cacheAgeMs in 0..CACHE_TTL_MS) {
            return parseAndValidate(cachedJson)
        }

        val json = fetchWithRetry() ?: return UpdateCheckResult.Failed(
            UpdateCheckErrorCode.NETWORK,
            "Не удалось загрузить update.json"
        )
        settings.setCachedUpdateMetadataJson(json)
        settings.setCachedUpdateMetadataTsMs(now)
        return parseAndValidate(json)
    }

    fun verifySha256(file: File, expectedSha256: String): Boolean {
        return sha256(file).equals(expectedSha256.trim(), ignoreCase = true)
    }

    private fun fetchWithRetry(): String? {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return fetchJson()
            } catch (e: Exception) {
                lastError = e
                logW("Update check attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < MAX_ATTEMPTS - 1) {
                    sleepProvider(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        logE("Failed to fetch update metadata", lastError)
        return null
    }

    private fun fetchJson(): String {
        if (fetchProvider != null) {
            return fetchProvider.invoke() ?: throw IllegalStateException("Fetch provider returned null")
        }
        val connection = (URL(metadataUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                return input.bufferedReader().readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAndValidate(json: String): UpdateCheckResult {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val metadata = UpdateMetadata(
                versionCode = obj.getInt("versionCode", 0),
                versionName = obj.getString("versionName", ""),
                apkUrl = obj.getString("apkUrl", ""),
                sha256 = obj.getString("sha256", ""),
                signature = obj.getString("signature", ""),
                notesRu = obj.getString("notesRu", ""),
                notesEn = obj.getString("notesEn", ""),
                minSupportedVersionCode = obj.getInt("minSupportedVersionCode", 1),
                minSdk = obj.getInt("minSdk", 21),
                publishedAt = obj.getString("publishedAt", "")
            )

            if (metadata.versionCode <= 0 || metadata.versionName.isBlank()) {
                return UpdateCheckResult.Failed(
                    UpdateCheckErrorCode.INVALID_VERSION,
                    "Некорректная версия в update.json"
                )
            }
            if (!metadata.apkUrl.startsWith("https://")) {
                return UpdateCheckResult.Failed(UpdateCheckErrorCode.INVALID_APK_URL, "Неверный apkUrl")
            }
            if (metadata.sha256.isBlank()) {
                return UpdateCheckResult.Failed(UpdateCheckErrorCode.MISSING_SHA256, "Отсутствует sha256")
            }
            if (metadata.signature.isBlank()) {
                return UpdateCheckResult.Failed(UpdateCheckErrorCode.MISSING_SIGNATURE, "Отсутствует signature")
            }
            if (!verifyMetadataSignature(metadata)) {
                return UpdateCheckResult.Failed(
                    UpdateCheckErrorCode.INVALID_SIGNATURE,
                    "Подпись update.json невалидна"
                )
            }
            if (sdkInt < metadata.minSdk) {
                return UpdateCheckResult.Failed(
                    UpdateCheckErrorCode.DEVICE_NOT_SUPPORTED,
                    "Устройство не поддерживается этой версией"
                )
            }

            val skippedVersionCode = settings.getSkippedUpdateVersionCode()
            if (metadata.versionCode <= currentVersionCode) {
                UpdateCheckResult.UpToDate
            } else if (skippedVersionCode == metadata.versionCode) {
                UpdateCheckResult.UpToDate
            } else if (currentVersionCode < metadata.minSupportedVersionCode) {
                UpdateCheckResult.Failed(
                    UpdateCheckErrorCode.APP_VERSION_TOO_OLD,
                    "Текущая версия приложения слишком старая для прямого обновления"
                )
            } else {
                UpdateCheckResult.UpdateAvailable(metadata)
            }
        } catch (e: Exception) {
            logE("Failed to parse update metadata", e)
            UpdateCheckResult.Failed(UpdateCheckErrorCode.INVALID_JSON, "Некорректный формат update.json")
        }
    }

    private fun JsonObject.getString(name: String, fallback: String): String {
        val element = get(name) ?: return fallback
        return if (element.isJsonNull) fallback else element.asString
    }

    private fun JsonObject.getInt(name: String, fallback: Int): Int {
        val element = get(name) ?: return fallback
        return if (element.isJsonNull) fallback else runCatching { element.asInt }.getOrDefault(fallback)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun verifyMetadataSignature(metadata: UpdateMetadata): Boolean {
        return try {
            val publicKeyBytes = Base64.getDecoder().decode(signingPublicKeyDerBase64)
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(buildSigningPayload(metadata).toByteArray(StandardCharsets.UTF_8))
            val signatureBytes = Base64.getDecoder().decode(metadata.signature)
            verifier.verify(signatureBytes)
        } catch (e: Exception) {
            logE("Metadata signature verification failed", e)
            false
        }
    }

    private fun buildSigningPayload(metadata: UpdateMetadata): String {
        return listOf(
            "versionCode=${metadata.versionCode}",
            "versionName=${metadata.versionName}",
            "apkUrl=${metadata.apkUrl}",
            "sha256=${metadata.sha256.lowercase(Locale.US)}",
            "notesRu=${metadata.notesRu}",
            "notesEn=${metadata.notesEn}",
            "publishedAt=${metadata.publishedAt}",
            "minSupportedVersionCode=${metadata.minSupportedVersionCode}",
            "minSdk=${metadata.minSdk}"
        ).joinToString("\n")
    }

    private fun logW(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log is not mocked in local JVM tests.
        }
    }

    private fun logE(message: String, throwable: Throwable?) {
        try {
            Log.e(TAG, message, throwable)
        } catch (_: RuntimeException) {
            // android.util.Log is not mocked in local JVM tests.
        }
    }
}
