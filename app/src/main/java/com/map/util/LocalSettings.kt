// LocalSettings.kt
package com.map.util

import android.content.Context
import android.content.SharedPreferences
import com.map.update.UpdateSettingsStore

/**
 * SharedPreferences wrapper for MAP settings.
 * Stores proxy configuration and preferences.
 */
class LocalSettings(context: Context) : UpdateSettingsStore {
    companion object {
        private const val SETTINGS_NAME = "map_settings"

        // Local proxy auth
        const val KEY_LOCAL_PROXY_USER = "local_proxy_user"
        const val KEY_LOCAL_PROXY_PASS = "local_proxy_pass"

        // SSTP settings
        const val KEY_SSTP_ENABLED = "sstp_enabled"
        const val KEY_SSTP_HOST = "sstp_host"
        const val KEY_SSTP_PORT = "sstp_port"
        const val KEY_SSTP_USER = "sstp_user"
        const val KEY_SSTP_PASS = "sstp_pass"
        const val KEY_SSTP_IGNORE_CERT_ERRORS = "sstp_ignore_cert_errors"
        const val KEY_SSTP_RECONNECT_ENABLED = "sstp_reconnect_enabled"
        const val KEY_SSTP_RECONNECT_DELAY_SEC = "sstp_reconnect_delay_sec"
        const val KEY_SSTP_RECONNECT_ATTEMPTS = "sstp_reconnect_attempts"
        const val KEY_SSTP_CONNECTION_LOGGING = "sstp_connection_logging"
        /** Manual MTU for VPN TUN ([android.net.VpnService.Builder.setMtu], API 29+). */
        const val KEY_SSTP_TUN_MTU = "sstp_tun_mtu"

        // Runtime state
        const val KEY_RUNTIME_PROXY_RUNNING = "runtime_proxy_running"
        const val KEY_RUNTIME_PROXY_MODE = "runtime_proxy_mode"
        const val KEY_RUNTIME_SSTP_STATUS = "runtime_sstp_status"
        const val KEY_RUNTIME_REMOTE_PROXY_STATUS = "runtime_remote_proxy_status"
        const val KEY_PROXY_HEALTHCHECK_HOST = "proxy_healthcheck_host"
        const val KEY_PROXY_HEALTHCHECK_INTERVAL_SEC = "proxy_healthcheck_interval_sec"
        const val KEY_UPDATE_SKIPPED_VERSION_CODE = "update_skipped_version_code"
        const val KEY_UPDATE_CACHED_METADATA_JSON = "update_cached_metadata_json"
        const val KEY_UPDATE_CACHED_METADATA_TS_MS = "update_cached_metadata_ts_ms"

        const val PROXY_MODE_STOPPED = "stopped"
        const val PROXY_MODE_PASSTHROUGH = "passthrough"
        const val PROXY_MODE_CASCADE = "cascade"

        const val STATUS_DISABLED = "disabled"
        const val STATUS_INACTIVE = "inactive"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_WAITING = "waiting"
        const val STATUS_RECONNECTING = "reconnecting"
        const val STATUS_UNREACHABLE = "unreachable"
        const val STATUS_ERROR = "error"
        const val STATUS_UNSUPPORTED = "unsupported"
        
        // SOCKS5 settings
        const val KEY_SOCKS5_ENABLED = "socks5_enabled"
        const val KEY_SOCKS5_PORT = "socks5_port"
        const val KEY_SOCKS5_BIND_ALL = "socks5_bind_all"
        const val KEY_SOCKS5_UPSTREAM_ENABLED = "socks5_upstream_enabled"
        const val KEY_SOCKS5_UPSTREAM_HOST = "socks5_upstream_host"
        const val KEY_SOCKS5_UPSTREAM_PORT = "socks5_upstream_port"
        const val KEY_SOCKS5_UPSTREAM_PROTOCOL = "socks5_upstream_protocol"
        const val KEY_SOCKS5_UPSTREAM_USER = "socks5_upstream_user"
        const val KEY_SOCKS5_UPSTREAM_PASS = "socks5_upstream_pass"
        
        // HTTP/HTTPS settings
        const val KEY_HTTP_ENABLED = "http_enabled"
        const val KEY_HTTP_PORT = "http_port"
        const val KEY_HTTP_BIND_ALL = "http_bind_all"
        const val KEY_HTTP_UPSTREAM_ENABLED = "http_upstream_enabled"
        const val KEY_HTTP_UPSTREAM_HOST = "http_upstream_host"
        const val KEY_HTTP_UPSTREAM_PORT = "http_upstream_port"
        const val KEY_HTTP_UPSTREAM_PROTOCOL = "http_upstream_protocol"
        const val KEY_HTTP_UPSTREAM_USER = "http_upstream_user"
        const val KEY_HTTP_UPSTREAM_PASS = "http_upstream_pass"
        
        // General settings
        const val KEY_LANGUAGE = "language"
        const val KEY_BACKGROUND_MODE = "background_mode"
        
        const val DEFAULT_SOCKS5_PORT = 1080
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_SSTP_PORT = 443
        const val DEFAULT_SSTP_RECONNECT_DELAY_SEC = 5
        const val DEFAULT_SSTP_RECONNECT_ATTEMPTS = 3
        /** Default inner MTU for SSTP VPN TUN (bytes). */
        const val DEFAULT_SSTP_TUN_MTU = 1300
        /** Android VpnService.Builder.setMtu requires at least 1280. */
        const val MIN_SSTP_TUN_MTU = 1280
        const val MAX_SSTP_TUN_MTU = 1500
        const val DEFAULT_PROXY_HEALTHCHECK_HOST = "1.1.1.1"
        const val DEFAULT_PROXY_HEALTHCHECK_INTERVAL_SEC = 30
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
        
        // Reserved ports that typically require root
        private val RESERVED_PORTS = setOf(80, 443, 21, 22, 23, 25, 53)
    }
    
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)

    // ========== Local Proxy Auth ==========

    fun getLocalProxyUser(): String = prefs.getString(KEY_LOCAL_PROXY_USER, "") ?: ""
    fun setLocalProxyUser(user: String) = prefs.edit().putString(KEY_LOCAL_PROXY_USER, user).apply()

    fun getLocalProxyPass(): String = prefs.getString(KEY_LOCAL_PROXY_PASS, "") ?: ""
    fun setLocalProxyPass(pass: String) = prefs.edit().putString(KEY_LOCAL_PROXY_PASS, pass).apply()

    fun hasLocalProxyCredentials(): Boolean {
        return getLocalProxyUser().isNotBlank() && getLocalProxyPass().isNotBlank()
    }

    // ========== SSTP Settings ==========

    fun isSstpEnabled(): Boolean = prefs.getBoolean(KEY_SSTP_ENABLED, false)
    fun setSstpEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SSTP_ENABLED, enabled).apply()

    fun getSstpHost(): String = prefs.getString(KEY_SSTP_HOST, "") ?: ""
    fun setSstpHost(host: String) = prefs.edit().putString(KEY_SSTP_HOST, host.trim()).apply()

    fun getSstpPort(): Int {
        val port = prefs.getInt(KEY_SSTP_PORT, DEFAULT_SSTP_PORT)
        return if (isValidPort(port)) port else DEFAULT_SSTP_PORT
    }
    fun setSstpPort(port: Int): Boolean {
        if (!isValidPort(port)) return false
        prefs.edit().putInt(KEY_SSTP_PORT, port).apply()
        return true
    }

    fun getSstpUser(): String = prefs.getString(KEY_SSTP_USER, "") ?: ""
    fun setSstpUser(user: String) = prefs.edit().putString(KEY_SSTP_USER, user).apply()

    fun getSstpPass(): String = prefs.getString(KEY_SSTP_PASS, "") ?: ""
    fun setSstpPass(pass: String) = prefs.edit().putString(KEY_SSTP_PASS, pass).apply()

    fun isSstpIgnoreCertErrors(): Boolean = prefs.getBoolean(KEY_SSTP_IGNORE_CERT_ERRORS, false)
    fun setSstpIgnoreCertErrors(enabled: Boolean) = prefs.edit().putBoolean(KEY_SSTP_IGNORE_CERT_ERRORS, enabled).apply()

    fun isSstpReconnectEnabled(): Boolean = prefs.getBoolean(KEY_SSTP_RECONNECT_ENABLED, true)
    fun setSstpReconnectEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SSTP_RECONNECT_ENABLED, enabled).apply()

    fun getSstpReconnectDelaySec(): Int {
        val value = prefs.getInt(KEY_SSTP_RECONNECT_DELAY_SEC, DEFAULT_SSTP_RECONNECT_DELAY_SEC)
        return value.coerceAtLeast(1)
    }
    fun setSstpReconnectDelaySec(value: Int) = prefs.edit()
        .putInt(KEY_SSTP_RECONNECT_DELAY_SEC, value.coerceAtLeast(1))
        .apply()

    fun getSstpReconnectAttempts(): Int {
        val value = prefs.getInt(KEY_SSTP_RECONNECT_ATTEMPTS, DEFAULT_SSTP_RECONNECT_ATTEMPTS)
        return value.coerceAtLeast(1)
    }
    fun setSstpReconnectAttempts(value: Int) = prefs.edit()
        .putInt(KEY_SSTP_RECONNECT_ATTEMPTS, value.coerceAtLeast(1))
        .apply()

    fun isSstpConnectionLoggingEnabled(): Boolean = prefs.getBoolean(KEY_SSTP_CONNECTION_LOGGING, false)
    fun setSstpConnectionLoggingEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_SSTP_CONNECTION_LOGGING, enabled).apply()

    fun getSstpTunnelMtu(): Int {
        val value = prefs.getInt(KEY_SSTP_TUN_MTU, DEFAULT_SSTP_TUN_MTU)
        return value.coerceIn(MIN_SSTP_TUN_MTU, MAX_SSTP_TUN_MTU)
    }

    fun setSstpTunnelMtu(mtu: Int) {
        prefs.edit().putInt(KEY_SSTP_TUN_MTU, mtu.coerceIn(MIN_SSTP_TUN_MTU, MAX_SSTP_TUN_MTU)).apply()
    }

    fun isValidSstpTunnelMtu(mtu: Int): Boolean = mtu in MIN_SSTP_TUN_MTU..MAX_SSTP_TUN_MTU

    // ========== SOCKS5 Settings ==========
    
    fun isSocks5Enabled(): Boolean = prefs.getBoolean(KEY_SOCKS5_ENABLED, true)
    fun setSocks5Enabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SOCKS5_ENABLED, enabled).apply()
    
    fun getSocks5Port(): Int {
        val port = prefs.getInt(KEY_SOCKS5_PORT, DEFAULT_SOCKS5_PORT)
        return if (isValidPort(port)) port else DEFAULT_SOCKS5_PORT
    }
    fun setSocks5Port(port: Int): Boolean {
        if (!isValidPort(port)) return false
        prefs.edit().putInt(KEY_SOCKS5_PORT, port).apply()
        return true
    }
    
    fun isSocks5BindAll(): Boolean = prefs.getBoolean(KEY_SOCKS5_BIND_ALL, false)
    fun setSocks5BindAll(bindAll: Boolean) = prefs.edit().putBoolean(KEY_SOCKS5_BIND_ALL, bindAll).apply()
    
    fun getSocks5BindAddress(): String = if (isSocks5BindAll()) "0.0.0.0" else "127.0.0.1"
    
    fun isSocks5UpstreamEnabled(): Boolean = prefs.getBoolean(KEY_SOCKS5_UPSTREAM_ENABLED, false)
    fun setSocks5UpstreamEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SOCKS5_UPSTREAM_ENABLED, enabled).apply()
    
    fun getSocks5UpstreamHost(): String = prefs.getString(KEY_SOCKS5_UPSTREAM_HOST, "") ?: ""
    fun setSocks5UpstreamHost(host: String) = prefs.edit().putString(KEY_SOCKS5_UPSTREAM_HOST, host).apply()
    
    fun getSocks5UpstreamPort(): Int = prefs.getInt(KEY_SOCKS5_UPSTREAM_PORT, 1080)
    fun setSocks5UpstreamPort(port: Int) = prefs.edit().putInt(KEY_SOCKS5_UPSTREAM_PORT, port).apply()
    
    fun getSocks5UpstreamProtocol(): String = prefs.getString(KEY_SOCKS5_UPSTREAM_PROTOCOL, "SOCKS5") ?: "SOCKS5"
    fun setSocks5UpstreamProtocol(protocol: String) = prefs.edit().putString(KEY_SOCKS5_UPSTREAM_PROTOCOL, protocol).apply()
    
    fun getSocks5UpstreamUser(): String = prefs.getString(KEY_SOCKS5_UPSTREAM_USER, "") ?: ""
    fun setSocks5UpstreamUser(user: String) = prefs.edit().putString(KEY_SOCKS5_UPSTREAM_USER, user).apply()
    
    fun getSocks5UpstreamPass(): String = prefs.getString(KEY_SOCKS5_UPSTREAM_PASS, "") ?: ""
    fun setSocks5UpstreamPass(pass: String) = prefs.edit().putString(KEY_SOCKS5_UPSTREAM_PASS, pass).apply()
    
    // ========== HTTP/HTTPS Settings ==========
    
    fun isHttpEnabled(): Boolean = prefs.getBoolean(KEY_HTTP_ENABLED, true)
    fun setHttpEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_HTTP_ENABLED, enabled).apply()
    
    fun getHttpPort(): Int {
        val port = prefs.getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT)
        return if (isValidPort(port)) port else DEFAULT_HTTP_PORT
    }
    fun setHttpPort(port: Int): Boolean {
        if (!isValidPort(port)) return false
        prefs.edit().putInt(KEY_HTTP_PORT, port).apply()
        return true
    }
    
    fun isHttpBindAll(): Boolean = prefs.getBoolean(KEY_HTTP_BIND_ALL, false)
    fun setHttpBindAll(bindAll: Boolean) = prefs.edit().putBoolean(KEY_HTTP_BIND_ALL, bindAll).apply()
    
    fun getHttpBindAddress(): String = if (isHttpBindAll()) "0.0.0.0" else "127.0.0.1"
    
    fun isHttpUpstreamEnabled(): Boolean = prefs.getBoolean(KEY_HTTP_UPSTREAM_ENABLED, false)
    fun setHttpUpstreamEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_HTTP_UPSTREAM_ENABLED, enabled).apply()
    
    fun getHttpUpstreamHost(): String = prefs.getString(KEY_HTTP_UPSTREAM_HOST, "") ?: ""
    fun setHttpUpstreamHost(host: String) = prefs.edit().putString(KEY_HTTP_UPSTREAM_HOST, host).apply()
    
    fun getHttpUpstreamPort(): Int = prefs.getInt(KEY_HTTP_UPSTREAM_PORT, 8080)
    fun setHttpUpstreamPort(port: Int) = prefs.edit().putInt(KEY_HTTP_UPSTREAM_PORT, port).apply()
    
    fun getHttpUpstreamProtocol(): String = prefs.getString(KEY_HTTP_UPSTREAM_PROTOCOL, "HTTP") ?: "HTTP"
    fun setHttpUpstreamProtocol(protocol: String) = prefs.edit().putString(KEY_HTTP_UPSTREAM_PROTOCOL, protocol).apply()
    
    fun getHttpUpstreamUser(): String = prefs.getString(KEY_HTTP_UPSTREAM_USER, "") ?: ""
    fun setHttpUpstreamUser(user: String) = prefs.edit().putString(KEY_HTTP_UPSTREAM_USER, user).apply()
    
    fun getHttpUpstreamPass(): String = prefs.getString(KEY_HTTP_UPSTREAM_PASS, "") ?: ""
    fun setHttpUpstreamPass(pass: String) = prefs.edit().putString(KEY_HTTP_UPSTREAM_PASS, pass).apply()

    fun hasAnyUpstreamConfigured(): Boolean {
        val hasSocks5Upstream = isSocks5Enabled() && isSocks5UpstreamEnabled() && getSocks5UpstreamHost().isNotBlank()
        val hasHttpUpstream = isHttpEnabled() && isHttpUpstreamEnabled() && getHttpUpstreamHost().isNotBlank()
        return hasSocks5Upstream || hasHttpUpstream
    }

    fun getProxyHealthcheckHost(): String {
        val host = prefs.getString(KEY_PROXY_HEALTHCHECK_HOST, DEFAULT_PROXY_HEALTHCHECK_HOST) ?: DEFAULT_PROXY_HEALTHCHECK_HOST
        return host.trim().ifBlank { DEFAULT_PROXY_HEALTHCHECK_HOST }
    }

    fun setProxyHealthcheckHost(host: String) =
        prefs.edit().putString(KEY_PROXY_HEALTHCHECK_HOST, host.trim()).apply()

    fun getProxyHealthcheckIntervalSec(): Int {
        val value = prefs.getInt(KEY_PROXY_HEALTHCHECK_INTERVAL_SEC, DEFAULT_PROXY_HEALTHCHECK_INTERVAL_SEC)
        return value.coerceAtLeast(5)
    }

    fun setProxyHealthcheckIntervalSec(value: Int) =
        prefs.edit().putInt(KEY_PROXY_HEALTHCHECK_INTERVAL_SEC, value.coerceAtLeast(5)).apply()

    override fun getSkippedUpdateVersionCode(): Int = prefs.getInt(KEY_UPDATE_SKIPPED_VERSION_CODE, -1)

    override fun setSkippedUpdateVersionCode(versionCode: Int) =
        prefs.edit().putInt(KEY_UPDATE_SKIPPED_VERSION_CODE, versionCode).apply()

    override fun getCachedUpdateMetadataJson(): String =
        prefs.getString(KEY_UPDATE_CACHED_METADATA_JSON, "") ?: ""

    override fun setCachedUpdateMetadataJson(json: String) =
        prefs.edit().putString(KEY_UPDATE_CACHED_METADATA_JSON, json).apply()

    override fun getCachedUpdateMetadataTsMs(): Long =
        prefs.getLong(KEY_UPDATE_CACHED_METADATA_TS_MS, 0L)

    override fun setCachedUpdateMetadataTsMs(tsMs: Long) =
        prefs.edit().putLong(KEY_UPDATE_CACHED_METADATA_TS_MS, tsMs).apply()

    // ========== Runtime State ==========

    fun isProxyRunning(): Boolean = prefs.getBoolean(KEY_RUNTIME_PROXY_RUNNING, false)
    fun setProxyRunning(running: Boolean) = prefs.edit().putBoolean(KEY_RUNTIME_PROXY_RUNNING, running).apply()

    fun getRuntimeProxyMode(): String = prefs.getString(KEY_RUNTIME_PROXY_MODE, PROXY_MODE_STOPPED) ?: PROXY_MODE_STOPPED
    fun setRuntimeProxyMode(mode: String) = prefs.edit().putString(KEY_RUNTIME_PROXY_MODE, mode).apply()

    fun getRuntimeSstpStatus(): String = prefs.getString(KEY_RUNTIME_SSTP_STATUS, STATUS_INACTIVE) ?: STATUS_INACTIVE
    fun setRuntimeSstpStatus(status: String) = prefs.edit().putString(KEY_RUNTIME_SSTP_STATUS, status).apply()

    fun getRuntimeRemoteProxyStatus(): String = prefs.getString(KEY_RUNTIME_REMOTE_PROXY_STATUS, STATUS_INACTIVE) ?: STATUS_INACTIVE
    fun setRuntimeRemoteProxyStatus(status: String) = prefs.edit().putString(KEY_RUNTIME_REMOTE_PROXY_STATUS, status).apply()

    fun resetRuntimeState() {
        prefs.edit()
            .putBoolean(KEY_RUNTIME_PROXY_RUNNING, false)
            .putString(KEY_RUNTIME_PROXY_MODE, PROXY_MODE_STOPPED)
            .putString(KEY_RUNTIME_SSTP_STATUS, if (isSstpEnabled()) STATUS_INACTIVE else STATUS_DISABLED)
            .putString(KEY_RUNTIME_REMOTE_PROXY_STATUS, STATUS_INACTIVE)
            .apply()
    }
    
    // ========== Validation ==========
    
    fun isValidPort(port: Int): Boolean {
        return port in MIN_PORT..MAX_PORT
    }
    
    fun isReservedPort(port: Int): Boolean {
        return port in RESERVED_PORTS || port < 1024
    }
    
    fun getLanguage(): String {
        val lang = prefs.getString(KEY_LANGUAGE, "ru") ?: "ru"
        return when (lang.lowercase()) {
            "en" -> "en"
            "ru" -> "ru"
            else -> "ru"
        }
    }
    
    fun setLanguage(lang: String) {
        val normalized = when (lang.lowercase()) {
            "en" -> "en"
            else -> "ru"
        }
        prefs.edit().putString(KEY_LANGUAGE, normalized).apply()
    }
    
    fun isBackgroundModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_MODE, false)
    }
    
    fun setBackgroundModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_MODE, enabled).apply()
    }
    
    /**
     * Clear all settings.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
