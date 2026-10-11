package com.map.update

data class UpdateMetadata(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val signature: String,
    val notesRu: String,
    val notesEn: String,
    val minSupportedVersionCode: Int,
    val minSdk: Int,
    val publishedAt: String
) {
    fun releaseNotes(lang: String): String {
        return if (lang.lowercase() == "en") {
            notesEn.ifBlank { notesRu }
        } else {
            notesRu.ifBlank { notesEn }
        }
    }
}

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class UpdateAvailable(val metadata: UpdateMetadata) : UpdateCheckResult()
    data class Failed(val code: UpdateCheckErrorCode, val reason: String) : UpdateCheckResult()
}

enum class UpdateCheckErrorCode {
    NETWORK,
    INVALID_JSON,
    INVALID_VERSION,
    INVALID_APK_URL,
    MISSING_SHA256,
    MISSING_SIGNATURE,
    INVALID_SIGNATURE,
    DEVICE_NOT_SUPPORTED,
    APP_VERSION_TOO_OLD
}
