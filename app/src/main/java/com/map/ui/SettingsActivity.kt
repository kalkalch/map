// SettingsActivity.kt
package com.map.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.map.R
import com.map.util.LocalSettings
import com.map.util.SstpConnectionLog

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var settings: LocalSettings
    
    // SOCKS5 views
    private lateinit var switchSocks5Enabled: SwitchMaterial
    private lateinit var editSocks5Port: EditText
    private lateinit var switchSocks5BindAll: SwitchMaterial
    private lateinit var switchSocks5Upstream: SwitchMaterial
    private lateinit var layoutSocks5Upstream: LinearLayout
    private lateinit var editSocks5UpstreamHost: EditText
    private lateinit var editSocks5UpstreamPort: EditText
    private lateinit var radioGroupSocks5UpstreamProtocol: RadioGroup
    private lateinit var radioSocks5UpstreamHttp: RadioButton
    private lateinit var radioSocks5UpstreamSocks5: RadioButton
    private lateinit var editSocks5UpstreamUser: EditText
    private lateinit var editSocks5UpstreamPass: EditText
    
    // HTTP views
    private lateinit var switchHttpEnabled: SwitchMaterial
    private lateinit var editHttpPort: EditText
    private lateinit var switchHttpBindAll: SwitchMaterial
    private lateinit var switchHttpUpstream: SwitchMaterial
    private lateinit var layoutHttpUpstream: LinearLayout
    private lateinit var editHttpUpstreamHost: EditText
    private lateinit var editHttpUpstreamPort: EditText
    private lateinit var radioGroupHttpUpstreamProtocol: RadioGroup
    private lateinit var radioHttpUpstreamHttp: RadioButton
    private lateinit var radioHttpUpstreamSocks5: RadioButton
    private lateinit var editHttpUpstreamUser: EditText
    private lateinit var editHttpUpstreamPass: EditText

    // Local auth views
    private lateinit var editLocalProxyUser: EditText
    private lateinit var editLocalProxyPass: EditText
    private lateinit var editProxyHealthcheckHost: EditText
    private lateinit var editProxyHealthcheckPort: EditText
    private lateinit var editProxyHealthcheckIntervalSec: EditText
    private lateinit var switchAutoUpdateCheck: SwitchMaterial

    // SSTP views
    private lateinit var switchSstpEnabled: SwitchMaterial
    private lateinit var editSstpHost: EditText
    private lateinit var editSstpPort: EditText
    private lateinit var editSstpUser: EditText
    private lateinit var editSstpPass: EditText
    private lateinit var editSstpTunMtu: EditText
    private lateinit var switchSstpIgnoreCertErrors: SwitchMaterial
    private lateinit var switchSstpReconnect: SwitchMaterial
    private lateinit var editSstpReconnectDelay: EditText
    private lateinit var editSstpReconnectAttempts: EditText
    private lateinit var switchSstpConnectionLogging: SwitchMaterial
    private lateinit var buttonShareSstpLog: MaterialButton
    
    // Language views
    private lateinit var radioGroupLanguage: RadioGroup
    private lateinit var radioRu: RadioButton
    private lateinit var radioEn: RadioButton

    // Accordion views
    private lateinit var headerLocal: LinearLayout
    private lateinit var iconLocal: ImageView
    private lateinit var contentLocal: LinearLayout
    private lateinit var headerProxy: LinearLayout
    private lateinit var iconProxy: ImageView
    private lateinit var contentProxy: LinearLayout
    private lateinit var headerVpn: LinearLayout
    private lateinit var iconVpn: ImageView
    private lateinit var contentVpn: LinearLayout
    private lateinit var headerGeneral: LinearLayout
    private lateinit var iconGeneral: ImageView
    private lateinit var contentGeneral: LinearLayout
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        settings = LocalSettings(this)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.btn_settings)
        }
        
        initViews()
        loadSettings()
        setupListeners()
    }
    
    private fun initViews() {
        // SOCKS5 views
        switchSocks5Enabled = findViewById(R.id.switch_socks5_enabled)
        editSocks5Port = findViewById(R.id.edit_socks5_port)
        switchSocks5BindAll = findViewById(R.id.switch_socks5_bind_all)
        switchSocks5Upstream = findViewById(R.id.switch_socks5_upstream)
        layoutSocks5Upstream = findViewById(R.id.layout_socks5_upstream)
        editSocks5UpstreamHost = findViewById(R.id.edit_socks5_upstream_host)
        editSocks5UpstreamPort = findViewById(R.id.edit_socks5_upstream_port)
        radioGroupSocks5UpstreamProtocol = findViewById(R.id.radio_group_socks5_upstream_protocol)
        radioSocks5UpstreamHttp = findViewById(R.id.radio_socks5_upstream_http)
        radioSocks5UpstreamSocks5 = findViewById(R.id.radio_socks5_upstream_socks5)
        editSocks5UpstreamUser = findViewById(R.id.edit_socks5_upstream_user)
        editSocks5UpstreamPass = findViewById(R.id.edit_socks5_upstream_pass)
        
        // HTTP views
        switchHttpEnabled = findViewById(R.id.switch_http_enabled)
        editHttpPort = findViewById(R.id.edit_http_port)
        switchHttpBindAll = findViewById(R.id.switch_http_bind_all)
        switchHttpUpstream = findViewById(R.id.switch_http_upstream)
        layoutHttpUpstream = findViewById(R.id.layout_http_upstream)
        editHttpUpstreamHost = findViewById(R.id.edit_http_upstream_host)
        editHttpUpstreamPort = findViewById(R.id.edit_http_upstream_port)
        radioGroupHttpUpstreamProtocol = findViewById(R.id.radio_group_http_upstream_protocol)
        radioHttpUpstreamHttp = findViewById(R.id.radio_http_upstream_http)
        radioHttpUpstreamSocks5 = findViewById(R.id.radio_http_upstream_socks5)
        editHttpUpstreamUser = findViewById(R.id.edit_http_upstream_user)
        editHttpUpstreamPass = findViewById(R.id.edit_http_upstream_pass)

        editLocalProxyUser = findViewById(R.id.edit_local_proxy_user)
        editLocalProxyPass = findViewById(R.id.edit_local_proxy_pass)
        editProxyHealthcheckHost = findViewById(R.id.edit_proxy_healthcheck_host)
        editProxyHealthcheckPort = findViewById(R.id.edit_proxy_healthcheck_port)
        editProxyHealthcheckIntervalSec = findViewById(R.id.edit_proxy_healthcheck_interval_sec)
        switchAutoUpdateCheck = findViewById(R.id.switch_auto_update_check)

        switchSstpEnabled = findViewById(R.id.switch_sstp_enabled)
        editSstpHost = findViewById(R.id.edit_sstp_host)
        editSstpPort = findViewById(R.id.edit_sstp_port)
        editSstpUser = findViewById(R.id.edit_sstp_user)
        editSstpPass = findViewById(R.id.edit_sstp_pass)
        editSstpTunMtu = findViewById(R.id.edit_sstp_tun_mtu)
        switchSstpIgnoreCertErrors = findViewById(R.id.switch_sstp_ignore_cert_errors)
        switchSstpReconnect = findViewById(R.id.switch_sstp_reconnect)
        editSstpReconnectDelay = findViewById(R.id.edit_sstp_reconnect_delay)
        editSstpReconnectAttempts = findViewById(R.id.edit_sstp_reconnect_attempts)
        switchSstpConnectionLogging = findViewById(R.id.switch_sstp_connection_logging)
        buttonShareSstpLog = findViewById(R.id.button_share_sstp_log)
        
        // Language views
        radioGroupLanguage = findViewById(R.id.radio_group_language)
        radioRu = findViewById(R.id.radio_ru)
        radioEn = findViewById(R.id.radio_en)

        // Accordion views
        headerLocal = findViewById(R.id.header_local)
        iconLocal = findViewById(R.id.icon_local)
        contentLocal = findViewById(R.id.content_local)
        headerProxy = findViewById(R.id.header_proxy)
        iconProxy = findViewById(R.id.icon_proxy)
        contentProxy = findViewById(R.id.content_proxy)
        headerVpn = findViewById(R.id.header_vpn)
        iconVpn = findViewById(R.id.icon_vpn)
        contentVpn = findViewById(R.id.content_vpn)
        headerGeneral = findViewById(R.id.header_general)
        iconGeneral = findViewById(R.id.icon_general)
        contentGeneral = findViewById(R.id.content_general)
    }
    
    private fun setupListeners() {
        // Accordion listeners
        setupAccordion(headerLocal, iconLocal, contentLocal)
        setupAccordion(headerProxy, iconProxy, contentProxy)
        setupAccordion(headerVpn, iconVpn, contentVpn)
        setupAccordion(headerGeneral, iconGeneral, contentGeneral)
        
        switchSocks5Upstream.setOnCheckedChangeListener { _, isChecked ->
            layoutSocks5Upstream.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        switchHttpUpstream.setOnCheckedChangeListener { _, isChecked ->
            layoutHttpUpstream.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        switchSstpConnectionLogging.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                SstpConnectionLog.onLoggingPreferenceChanged(false)
            }
            updateSstpLogUi()
        }

        buttonShareSstpLog.setOnClickListener {
            val shareIntent = SstpConnectionLog.buildShareIntent(this)
            if (shareIntent == null) {
                showToast(getString(R.string.sstp_log_missing))
                updateSstpLogUi()
                return@setOnClickListener
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.sstp_log_share_title)))
        }
    }

    private fun setupAccordion(header: LinearLayout, icon: ImageView, content: LinearLayout) {
        header.setOnClickListener {
            val isVisible = content.visibility == View.VISIBLE
            content.visibility = if (isVisible) View.GONE else View.VISIBLE
            icon.setImageResource(
                if (isVisible) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
        }
    }
    
    private fun loadSettings() {
        // Load SOCKS5 settings
        switchSocks5Enabled.isChecked = settings.isSocks5Enabled()
        editSocks5Port.setText(settings.getSocks5Port().toString())
        switchSocks5BindAll.isChecked = settings.isSocks5BindAll()
        switchSocks5Upstream.isChecked = settings.isSocks5UpstreamEnabled()
        layoutSocks5Upstream.visibility = if (settings.isSocks5UpstreamEnabled()) View.VISIBLE else View.GONE
        editSocks5UpstreamHost.setText(settings.getSocks5UpstreamHost())
        editSocks5UpstreamPort.setText(settings.getSocks5UpstreamPort().toString())
        
        when (settings.getSocks5UpstreamProtocol()) {
            "HTTP" -> radioSocks5UpstreamHttp.isChecked = true
            else -> radioSocks5UpstreamSocks5.isChecked = true
        }
        
        editSocks5UpstreamUser.setText(settings.getSocks5UpstreamUser())
        editSocks5UpstreamPass.setText(settings.getSocks5UpstreamPass())
        
        // Load HTTP settings
        switchHttpEnabled.isChecked = settings.isHttpEnabled()
        editHttpPort.setText(settings.getHttpPort().toString())
        switchHttpBindAll.isChecked = settings.isHttpBindAll()
        switchHttpUpstream.isChecked = settings.isHttpUpstreamEnabled()
        layoutHttpUpstream.visibility = if (settings.isHttpUpstreamEnabled()) View.VISIBLE else View.GONE
        editHttpUpstreamHost.setText(settings.getHttpUpstreamHost())
        editHttpUpstreamPort.setText(settings.getHttpUpstreamPort().toString())
        
        when (settings.getHttpUpstreamProtocol()) {
            "SOCKS5" -> radioHttpUpstreamSocks5.isChecked = true
            else -> radioHttpUpstreamHttp.isChecked = true
        }
        
        editHttpUpstreamUser.setText(settings.getHttpUpstreamUser())
        editHttpUpstreamPass.setText(settings.getHttpUpstreamPass())

        editLocalProxyUser.setText(settings.getLocalProxyUser())
        editLocalProxyPass.setText(settings.getLocalProxyPass())
        editProxyHealthcheckHost.setText(settings.getProxyHealthcheckHost())
        editProxyHealthcheckPort.setText(settings.getProxyHealthcheckPort().toString())
        editProxyHealthcheckIntervalSec.setText(settings.getProxyHealthcheckIntervalSec().toString())
        switchAutoUpdateCheck.isChecked = settings.isAutoUpdateCheckEnabled()

        switchSstpEnabled.isChecked = settings.isSstpEnabled()
        editSstpHost.setText(settings.getSstpHost())
        editSstpPort.setText(settings.getSstpPort().toString())
        editSstpUser.setText(settings.getSstpUser())
        editSstpPass.setText(settings.getSstpPass())
        editSstpTunMtu.setText(settings.getSstpTunnelMtu().toString())
        switchSstpIgnoreCertErrors.isChecked = settings.isSstpIgnoreCertErrors()
        switchSstpReconnect.isChecked = settings.isSstpReconnectEnabled()
        editSstpReconnectDelay.setText(settings.getSstpReconnectDelaySec().toString())
        editSstpReconnectAttempts.setText(settings.getSstpReconnectAttempts().toString())
        switchSstpConnectionLogging.isChecked = settings.isSstpConnectionLoggingEnabled()
        updateSstpLogUi()
        
        // Load language
        when (settings.getLanguage()) {
            "en" -> radioEn.isChecked = true
            else -> radioRu.isChecked = true
        }
    }
    
    private fun saveSettings() {
        var hasError = false
        
        // Save SOCKS5 settings
        settings.setSocks5Enabled(switchSocks5Enabled.isChecked)
        
        val socks5Port = editSocks5Port.text.toString().toIntOrNull()
        if (socks5Port == null || !settings.isValidPort(socks5Port)) {
            showToast("Неверный порт SOCKS5")
            hasError = true
        } else {
            settings.setSocks5Port(socks5Port)
        }
        
        settings.setSocks5BindAll(switchSocks5BindAll.isChecked)
        settings.setSocks5UpstreamEnabled(switchSocks5Upstream.isChecked)
        settings.setSocks5UpstreamHost(editSocks5UpstreamHost.text.toString())
        
        val socks5UpstreamPort = editSocks5UpstreamPort.text.toString().toIntOrNull() ?: 1080
        settings.setSocks5UpstreamPort(socks5UpstreamPort)
        
        val socks5UpstreamProtocol = when (radioGroupSocks5UpstreamProtocol.checkedRadioButtonId) {
            R.id.radio_socks5_upstream_http -> "HTTP"
            else -> "SOCKS5"
        }
        settings.setSocks5UpstreamProtocol(socks5UpstreamProtocol)
        
        settings.setSocks5UpstreamUser(editSocks5UpstreamUser.text.toString())
        settings.setSocks5UpstreamPass(editSocks5UpstreamPass.text.toString())
        
        // Save HTTP settings
        settings.setHttpEnabled(switchHttpEnabled.isChecked)
        
        val httpPort = editHttpPort.text.toString().toIntOrNull()
        if (httpPort == null || !settings.isValidPort(httpPort)) {
            showToast("Неверный порт HTTP/HTTPS")
            hasError = true
        } else {
            settings.setHttpPort(httpPort)
        }
        
        settings.setHttpBindAll(switchHttpBindAll.isChecked)
        settings.setHttpUpstreamEnabled(switchHttpUpstream.isChecked)
        settings.setHttpUpstreamHost(editHttpUpstreamHost.text.toString())
        
        val httpUpstreamPort = editHttpUpstreamPort.text.toString().toIntOrNull() ?: 8080
        settings.setHttpUpstreamPort(httpUpstreamPort)
        
        val httpUpstreamProtocol = when (radioGroupHttpUpstreamProtocol.checkedRadioButtonId) {
            R.id.radio_http_upstream_socks5 -> "SOCKS5"
            else -> "HTTP"
        }
        settings.setHttpUpstreamProtocol(httpUpstreamProtocol)
        
        settings.setHttpUpstreamUser(editHttpUpstreamUser.text.toString())
        settings.setHttpUpstreamPass(editHttpUpstreamPass.text.toString())

        val localProxyUser = editLocalProxyUser.text.toString()
        val localProxyPass = editLocalProxyPass.text.toString()
        if ((localProxyUser.isBlank() && localProxyPass.isNotBlank()) ||
            (localProxyUser.isNotBlank() && localProxyPass.isBlank())
        ) {
            showToast("Для локального прокси нужны и логин, и пароль")
            hasError = true
        } else {
            settings.setLocalProxyUser(localProxyUser)
            settings.setLocalProxyPass(localProxyPass)
        }

        val healthcheckHostRaw = editProxyHealthcheckHost.text.toString().trim()
        var healthcheckHost = healthcheckHostRaw
        var hostPortFromHostField: Int? = null
        // Backward compatibility: allow accidental "host:port" input in host field.
        val singleColonIndex = healthcheckHostRaw.lastIndexOf(':')
        if (singleColonIndex > 0 && healthcheckHostRaw.indexOf(':') == singleColonIndex) {
            val portCandidate = healthcheckHostRaw.substring(singleColonIndex + 1)
            val parsedPort = portCandidate.toIntOrNull()
            if (parsedPort != null && settings.isValidPort(parsedPort)) {
                healthcheckHost = healthcheckHostRaw.substring(0, singleColonIndex).trim()
                hostPortFromHostField = parsedPort
            }
        }

        if (healthcheckHost.isBlank()) {
            showToast("Укажите адрес проверки через прокси")
            hasError = true
        } else {
            settings.setProxyHealthcheckHost(healthcheckHost)
        }

        val healthcheckPortInput = editProxyHealthcheckPort.text.toString().toIntOrNull()
        val healthcheckPort = hostPortFromHostField ?: healthcheckPortInput
        if (healthcheckPort == null || !settings.isValidPort(healthcheckPort)) {
            showToast("Неверный порт проверки каскада (1–65535)")
            hasError = true
        } else {
            settings.setProxyHealthcheckPort(healthcheckPort)
        }

        val healthcheckInterval = editProxyHealthcheckIntervalSec.text.toString().toIntOrNull()
        if (healthcheckInterval == null || healthcheckInterval < 5) {
            showToast("Интервал проверок должен быть не меньше 5 сек")
            hasError = true
        } else {
            settings.setProxyHealthcheckIntervalSec(healthcheckInterval)
        }
        settings.setAutoUpdateCheckEnabled(switchAutoUpdateCheck.isChecked)

        settings.setSstpEnabled(switchSstpEnabled.isChecked)
        val sstpHost = editSstpHost.text.toString()
        if (switchSstpEnabled.isChecked && sstpHost.isBlank()) {
            showToast("Укажите адрес SSTP сервера")
            hasError = true
        }
        settings.setSstpHost(sstpHost)
        val sstpPort = editSstpPort.text.toString().toIntOrNull()
        if (sstpPort == null || !settings.isValidPort(sstpPort)) {
            showToast("Неверный порт SSTP")
            hasError = true
        } else {
            settings.setSstpPort(sstpPort)
        }
        settings.setSstpUser(editSstpUser.text.toString())
        settings.setSstpPass(editSstpPass.text.toString())

        val sstpTunMtu = editSstpTunMtu.text.toString().toIntOrNull()
        if (sstpTunMtu == null || !settings.isValidSstpTunnelMtu(sstpTunMtu)) {
            showToast(getString(R.string.sstp_tun_mtu_invalid))
            hasError = true
        } else {
            settings.setSstpTunnelMtu(sstpTunMtu)
        }

        settings.setSstpIgnoreCertErrors(switchSstpIgnoreCertErrors.isChecked)
        settings.setSstpReconnectEnabled(switchSstpReconnect.isChecked)

        val reconnectDelay = editSstpReconnectDelay.text.toString().toIntOrNull()
        if (reconnectDelay == null || reconnectDelay < 1) {
            showToast("Неверная задержка реконнекта SSTP")
            hasError = true
        } else {
            settings.setSstpReconnectDelaySec(reconnectDelay)
        }

        val reconnectAttempts = editSstpReconnectAttempts.text.toString().toIntOrNull()
        if (reconnectAttempts == null || reconnectAttempts < 1) {
            showToast("Неверное число попыток реконнекта SSTP")
            hasError = true
        } else {
            settings.setSstpReconnectAttempts(reconnectAttempts)
        }
        settings.setSstpConnectionLoggingEnabled(switchSstpConnectionLogging.isChecked)
        if (!switchSstpConnectionLogging.isChecked) {
            SstpConnectionLog.onLoggingPreferenceChanged(false)
        }
        
        // Save language
        val language = when (radioGroupLanguage.checkedRadioButtonId) {
            R.id.radio_en -> "en"
            else -> "ru"
        }
        settings.setLanguage(language)
        
        if (!hasError) {
            showToast(getString(R.string.success_saved))
            finish()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onResume() {
        super.onResume()
        updateSstpLogUi()
    }

    private fun updateSstpLogUi() {
        buttonShareSstpLog.isEnabled =
            switchSstpConnectionLogging.isChecked && SstpConnectionLog.hasLog()
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
