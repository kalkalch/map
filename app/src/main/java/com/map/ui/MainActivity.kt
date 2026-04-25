// MainActivity.kt
package com.map.ui

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.RECEIVER_EXPORTED
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.map.BuildConfig
import com.map.R
import com.map.databinding.ActivityMainBinding
import com.map.service.MapService
import com.map.service.MapVpnService
import com.map.update.GitHubUpdateChecker
import com.map.update.UpdateCheckErrorCode
import com.map.update.UpdateCheckResult
import com.map.update.UpdateMetadata
import com.map.util.LocalSettings
import com.map.util.NetworkUtil
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: LocalSettings
    private lateinit var updateChecker: GitHubUpdateChecker
    private var isProxyRunning = false
    private var pendingUpdate: UpdateMetadata? = null
    private var pendingDownloadId: Long = -1L
    @Volatile
    private var isUpdateCheckInProgress = false
    private var downloadReceiverRegistered = false
    private val runtimeStateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LocalSettings.KEY_RUNTIME_PROXY_RUNNING
            || key == LocalSettings.KEY_RUNTIME_PROXY_MODE
            || key == LocalSettings.KEY_RUNTIME_SSTP_STATUS
            || key == LocalSettings.KEY_RUNTIME_REMOTE_PROXY_STATUS
        ) {
            runOnUiThread { updateStatusUI() }
        }
    }
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE != intent.action) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId != pendingDownloadId || downloadId <= 0L) return
            onUpdateDownloadCompleted(downloadId)
        }
    }
    
    companion object {
        const val PORT_DEFAULT = 1080
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
        private const val REQUEST_VPN_PERMISSION = 1002
        const val EXTRA_ACTION_CHECK_UPDATES = "action_check_updates"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settings = LocalSettings(this)
        updateChecker = GitHubUpdateChecker(settings)
        initViews()
        updateStatusUI()
        requestNotificationPermissionIfNeeded()
        registerDownloadReceiver()
        val isManualUpdateCheck = intent?.getBooleanExtra(EXTRA_ACTION_CHECK_UPDATES, false) == true
        if (isManualUpdateCheck) {
            checkForUpdates(showUserFeedback = true)
        } else if (settings.isAutoUpdateCheckEnabled()) {
            checkForUpdates(showUserFeedback = false)
        }
    }
    
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showToast("Разрешение на уведомления необходимо для работы прокси в фоне")
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        settings.registerListener(runtimeStateListener)
        updateStatusUI()
    }

    override fun onPause() {
        settings.unregisterListener(runtimeStateListener)
        super.onPause()
    }

    override fun onDestroy() {
        if (downloadReceiverRegistered) {
            unregisterReceiver(downloadReceiver)
            downloadReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerDownloadReceiver() {
        val filter = android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ACTION_DOWNLOAD_COMPLETE is dispatched by a system component.
            // Use RECEIVER_EXPORTED to ensure delivery on modern Android versions.
            registerReceiver(downloadReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }
        downloadReceiverRegistered = true
    }
    
    private fun initViews() {
        binding.btnStartStop.setOnClickListener {
            toggleProxy()
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }
    
    private fun toggleProxy() {
        if (isProxyRunning) {
            stopProxy()
        } else {
            startProxy()
        }
    }
    
    private fun startProxy() {
        try {
            if (!settings.isSocks5Enabled() && !settings.isHttpEnabled()) {
                showToast("Включите хотя бы один тип прокси в настройках")
                return
            }

            if (settings.isSstpEnabled()) {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
                    return
                }
                startVpnAndProxy()
                return
            }

            startMapProxyService()
            
        } catch (e: Exception) {
            showToast(getString(R.string.error_start_failed) + ": ${e.message}")
        }
    }
    
    private fun stopProxy() {
        try {
            val intent = Intent(this, MapService::class.java).apply {
                putExtra("action", "stop")
            }
            startService(intent)

            val vpnIntent = Intent(this, MapVpnService::class.java).apply {
                putExtra(MapVpnService.EXTRA_ACTION, MapVpnService.ACTION_STOP)
            }
            startService(vpnIntent)
            
            settings.resetRuntimeState()
            isProxyRunning = false
            updateStatusUI()
            showToast(getString(R.string.success_stopped))
            
        } catch (e: Exception) {
            showToast("Ошибка остановки: ${e.message}")
        }
    }
    
    private fun updateStatusUI() {
        isProxyRunning = settings.isProxyRunning()
        val socks5Port = settings.getSocks5Port()
        val httpPort = settings.getHttpPort()
        
        if (isProxyRunning) {
            binding.labelStatus.text = getString(R.string.status_connected)
            binding.labelStatus.setTextColor(getStatusColor(LocalSettings.STATUS_CONNECTED))
            binding.btnStartStop.text = getString(R.string.btn_stop)
        } else {
            binding.labelStatus.text = getString(R.string.status_inactive)
            binding.labelStatus.setTextColor(getStatusColor(LocalSettings.STATUS_INACTIVE))
            binding.btnStartStop.text = getString(R.string.btn_start)
        }
        
        val addressInfo = buildString {
            if (settings.isSocks5Enabled()) {
                val socks5Address = if (settings.isSocks5BindAll()) {
                    NetworkUtil.getLocalIpAddress()
                } else {
                    "127.0.0.1"
                }
                append("SOCKS5: $socks5Address:$socks5Port")
            }
            if (settings.isHttpEnabled()) {
                if (settings.isSocks5Enabled()) append("\n")
                val httpAddress = if (settings.isHttpBindAll()) {
                    NetworkUtil.getLocalIpAddress()
                } else {
                    "127.0.0.1"
                }
                append("HTTP: $httpAddress:$httpPort")
            }
        }

        binding.labelIpAddress.text = addressInfo.ifEmpty { "Прокси не настроен" }
        binding.labelProxyMode.text = "Режим локального прокси: ${formatProxyMode(settings.getRuntimeProxyMode())}"
        binding.labelProxyMode.setTextColor(getProxyModeColor(settings.getRuntimeProxyMode()))
        binding.labelRemoteProxyStatus.text =
            "Удаленный прокси: ${formatStatus(settings.getRuntimeRemoteProxyStatus())}"
        binding.labelRemoteProxyStatus.setTextColor(getStatusColor(settings.getRuntimeRemoteProxyStatus()))
        binding.labelSstpStatus.text = "SSTP: ${formatStatus(settings.getRuntimeSstpStatus())}"
        binding.labelSstpStatus.setTextColor(getStatusColor(settings.getRuntimeSstpStatus()))
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates(showUserFeedback: Boolean) {
        if (isUpdateCheckInProgress) {
            return
        }
        isUpdateCheckInProgress = true
        if (showUserFeedback) {
            showToast(getString(R.string.update_checking))
        }
        Thread {
            val result = runCatching { updateChecker.check() }
                .getOrElse {
                    UpdateCheckResult.Failed(
                        UpdateCheckErrorCode.NETWORK,
                        it.localizedMessage ?: "Unexpected error"
                    )
                }
            runOnUiThread {
                isUpdateCheckInProgress = false
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                when (result) {
                    is UpdateCheckResult.UpToDate -> {
                        if (showUserFeedback) {
                            showToast(getString(R.string.update_not_available))
                        }
                    }
                    is UpdateCheckResult.Failed -> {
                        if (showUserFeedback) {
                            showToast(resolveUpdateError(result))
                        }
                    }
                    is UpdateCheckResult.UpdateAvailable -> showUpdateDialog(result.metadata)
                }
            }
        }.start()
    }

    private fun resolveUpdateError(result: UpdateCheckResult.Failed): String {
        return when (result.code) {
            UpdateCheckErrorCode.NETWORK -> getString(R.string.update_check_failed_network)
            UpdateCheckErrorCode.DEVICE_NOT_SUPPORTED -> getString(R.string.update_device_not_supported)
            UpdateCheckErrorCode.APP_VERSION_TOO_OLD -> getString(R.string.update_app_too_old)
            UpdateCheckErrorCode.INVALID_SIGNATURE -> getString(R.string.update_invalid_signature)
            else -> getString(R.string.update_check_failed, result.reason)
        }
    }

    private fun showUpdateDialog(metadata: UpdateMetadata) {
        val notes = metadata.releaseNotes(settings.getLanguage()).ifBlank { "-" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, metadata.versionName))
            .setMessage(notes)
            .setPositiveButton(R.string.update_download_install) { _, _ ->
                enqueueUpdateDownload(metadata)
            }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_skip_version) { _, _ ->
                settings.setSkippedUpdateVersionCode(metadata.versionCode)
                showToast(getString(R.string.update_skip_version))
            }
            .show()
    }

    private fun enqueueUpdateDownload(metadata: UpdateMetadata) {
        val request = DownloadManager.Request(Uri.parse(metadata.apkUrl))
            .setTitle("MAP ${metadata.versionName}")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                "map-${metadata.versionName}.apk"
            )

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        pendingDownloadId = manager.enqueue(request)
        pendingUpdate = metadata
        showToast(getString(R.string.update_download_started))
    }

    private fun onUpdateDownloadCompleted(downloadId: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                showToast(getString(R.string.update_download_failed))
                return
            }

            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                showToast(getString(R.string.update_download_failed))
                return
            }

            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val file = uriToFile(localUri) ?: run {
                showToast(getString(R.string.update_download_failed))
                return
            }
            val metadata = pendingUpdate ?: run {
                showToast(getString(R.string.update_download_failed))
                return
            }

            if (!updateChecker.verifySha256(file, metadata.sha256)) {
                file.delete()
                showToast(getString(R.string.update_verify_failed))
                return
            }
            installApk(file)
        }
    }

    private fun uriToFile(localUri: String?): File? {
        if (localUri.isNullOrBlank()) return null
        return when {
            localUri.startsWith("file://") -> File(Uri.parse(localUri).path ?: return null)
            else -> null
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            showToast(getString(R.string.update_install_permission_required))
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val apkUri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(installIntent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startVpnAndProxy()
            } else {
                showToast("Для SSTP нужно разрешение на VPN")
            }
        }
    }

    private fun startVpnAndProxy() {
        val vpnIntent = Intent(this, MapVpnService::class.java).apply {
            putExtra(MapVpnService.EXTRA_ACTION, MapVpnService.ACTION_PREPARE_ROUTE)
        }
        startService(vpnIntent)
        startMapProxyService()
    }

    private fun startMapProxyService() {
        val intent = Intent(this, MapService::class.java).apply {
            putExtra("action", "start")
        }
        startForegroundService(intent)

        settings.setProxyRunning(true)
        isProxyRunning = true
        updateStatusUI()
        showToast(getString(R.string.success_started))
    }

    private fun formatProxyMode(mode: String): String {
        return when (mode) {
            LocalSettings.PROXY_MODE_CASCADE -> "каскадирование"
            else -> "остановлен"
        }
    }

    private fun formatStatus(status: String): String {
        return when (status) {
            LocalSettings.STATUS_DISABLED -> "выключен"
            LocalSettings.STATUS_INACTIVE -> "неактивен"
            LocalSettings.STATUS_CONNECTING -> "подключение"
            LocalSettings.STATUS_RECONNECTING -> "реконнект"
            LocalSettings.STATUS_CONNECTED -> "подключен"
            LocalSettings.STATUS_WAITING -> "ожидание"
            LocalSettings.STATUS_UNREACHABLE -> "недоступен"
            LocalSettings.STATUS_ERROR -> "ошибка"
            LocalSettings.STATUS_UNSUPPORTED -> "не поддерживается"
            else -> status
        }
    }

    private fun getProxyModeColor(mode: String): Int {
        val colorRes = when (mode) {
            LocalSettings.PROXY_MODE_CASCADE -> R.color.status_active
            else -> R.color.status_inactive
        }
        return ContextCompat.getColor(this, colorRes)
    }

    private fun getStatusColor(status: String): Int {
        val colorRes = when (status) {
            LocalSettings.STATUS_CONNECTED -> R.color.status_active
            LocalSettings.STATUS_CONNECTING,
            LocalSettings.STATUS_RECONNECTING,
            LocalSettings.STATUS_WAITING -> R.color.status_transition
            LocalSettings.STATUS_UNREACHABLE,
            LocalSettings.STATUS_ERROR,
            LocalSettings.STATUS_UNSUPPORTED -> R.color.status_error
            LocalSettings.STATUS_DISABLED -> R.color.status_disabled
            LocalSettings.STATUS_INACTIVE -> R.color.status_inactive
            else -> R.color.status_inactive
        }
        return ContextCompat.getColor(this, colorRes)
    }
}
