package com.map.update

interface UpdateSettingsStore {
    fun getSkippedUpdateVersionCode(): Int
    fun setSkippedUpdateVersionCode(versionCode: Int)
    fun getCachedUpdateMetadataJson(): String
    fun setCachedUpdateMetadataJson(json: String)
    fun getCachedUpdateMetadataTsMs(): Long
    fun setCachedUpdateMetadataTsMs(tsMs: Long)
}
