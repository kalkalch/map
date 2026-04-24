// MapService.java
package com.map.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import timber.log.Timber;
import com.map.proxy.ProxyServer;
import com.map.proxy.Protocol;
import com.map.proxy.ProxyNode;
import com.map.sstp.SstpController;
import com.map.ui.MainActivity;
import com.map.util.LocalSettings;
import com.map.util.SstpConnectionLog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground Service for MAP Proxy - ensures background operation.
 * Required for Android 8.0+ compatibility.
 */
public class MapService extends Service {
    private static final String CHANNEL_ID = "MAP_PROXY_CHANNEL";
    private static final String CHANNEL_NAME = "MAP Proxy Service";
    private static final int NOTIFICATION_ID = 1;
    private static final int UPSTREAM_CHECK_TIMEOUT_MS = 7000;
    private static final int UPSTREAM_CHECK_ATTEMPTS = 3;
    private static final int UPSTREAM_CHECK_RETRY_DELAY_MS = 1200;
    private static final int LOCAL_SOCKS5_CHECK_TIMEOUT_MS = 3000;

    private ProxyServer socks5Server;
    private ProxyServer httpServer;
    private LocalSettings settings;
    private SstpController sstpController;
    private Thread startupThread;
    private Thread cascadeHealthThread;
    private volatile boolean stopCascadeHealthChecks;
    
    private static final class CascadeCheckResult {
        private final boolean ok;
        private final String detail;

        private CascadeCheckResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }

        private static CascadeCheckResult ok(String detail) {
            return new CascadeCheckResult(true, detail);
        }

        private static CascadeCheckResult fail(String detail) {
            return new CascadeCheckResult(false, detail);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        settings = new LocalSettings(this);
        sstpController = new SstpController();
        settings.resetRuntimeState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // CRITICAL: Start foreground IMMEDIATELY to prevent service kill on Android 8+
        startForeground(NOTIFICATION_ID, buildInitialNotification());
        
        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("start".equals(action)) {
                startProxiesAsync();
            } else if ("stop".equals(action)) {
                cancelStartupIfRunning();
                stopProxies();
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopProxies();
    }

    /**
     * Create notification channel for Android O+.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Уведомления о работе прокси-сервера");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Start proxy servers based on settings.
     */
    private void startProxiesAsync() {
        cancelStartupIfRunning();
        startupThread = new Thread(this::startProxies, "MAP-Proxy-Startup");
        startupThread.start();
    }

    private void cancelStartupIfRunning() {
        if (startupThread != null && startupThread.isAlive()) {
            if (Thread.currentThread() != startupThread) {
                startupThread.interrupt();
            }
        }
        if (Thread.currentThread() != startupThread) {
            startupThread = null;
        }
    }

    private void startProxies() {
        stopCascadeHealthChecks();
        stopProxyServersOnly();
        settings.setProxyRunning(true);
        settings.setRuntimeProxyMode(LocalSettings.PROXY_MODE_CASCADE);
        settings.setRuntimeRemoteProxyStatus(LocalSettings.STATUS_WAITING);
        settings.setRuntimeSstpStatus(
            settings.isSstpEnabled() ? LocalSettings.STATUS_CONNECTING : LocalSettings.STATUS_DISABLED
        );
        
        try {
            List<ProxyNode> socks5Chain = null;
            if (settings.isSocks5Enabled()) {
                socks5Chain = buildSocks5Chain();
                if (socks5Chain == null || socks5Chain.isEmpty()) {
                    throw new IOException("SOCKS5 upstream не настроен: passthrough отключен");
                }
            }

            List<ProxyNode> httpChain = null;
            if (settings.isHttpEnabled()) {
                httpChain = buildHttpChain();
                if (httpChain == null || httpChain.isEmpty()) {
                    throw new IOException("HTTP upstream не настроен: passthrough отключен");
                }
            }

            if (settings.isSocks5Enabled()) {
                socks5Server = new ProxyServer(this);
                socks5Server.start(
                    settings.getSocks5BindAddress(),
                    settings.getSocks5Port(), 
                    Protocol.SOCKS5, 
                    socks5Chain,
                    getLocalProxyCredentials()
                );
                SstpConnectionLog.log(
                    "MapService",
                    "Local SOCKS5 proxy started on " + settings.getSocks5BindAddress() + ":" + settings.getSocks5Port()
                );
                try {
                    verifyLocalSocks5(
                        settings.getSocks5BindAddress(),
                        settings.getSocks5Port()
                    );
                } catch (IOException e) {
                    // Local proxy is already bound; don't fail startup on best-effort diagnostic probe.
                    Timber.tag("MapService").w(e, "SOCKS5 local health check failed, continuing startup");
                    SstpConnectionLog.logError("MapService", "SOCKS5 local health check failed", e);
                }
            }
            
            // Start HTTP/HTTPS proxy
            if (settings.isHttpEnabled()) {
                httpServer = new ProxyServer(this);
                httpServer.start(
                    settings.getHttpBindAddress(),
                    settings.getHttpPort(), 
                    Protocol.HTTP, 
                    httpChain,
                    getLocalProxyCredentials()
                );
                SstpConnectionLog.log(
                    "MapService",
                    "Local HTTP proxy started on " + settings.getHttpBindAddress() + ":" + settings.getHttpPort()
                );
            }

            if (settings.isSstpConnectionLoggingEnabled()) {
                SstpConnectionLog.startSession(settings);
                SstpConnectionLog.log("MapService", "Starting SSTP connect flow");
            } else {
                SstpConnectionLog.clear();
            }
            SstpController.Result sstpResult = sstpController.connect(
                settings,
                MapVpnService.Companion.getActiveInstance(),
                (status, detail) -> {
                    settings.setRuntimeSstpStatus(status);
                    Timber.tag("MapService").i("SSTP status: %s (%s)", status, detail);
                    SstpConnectionLog.log("MapService", "SSTP status=" + status + " detail=" + detail);
                    updateNotification();
                }
            );
            settings.setRuntimeSstpStatus(sstpResult.getStatus());
            SstpConnectionLog.log(
                "MapService",
                "SSTP connect result status=" + sstpResult.getStatus() + " tunnelReady=" + sstpResult.isTunnelReady()
            );
            if (sstpResult.isTunnelReady()) {
                MapVpnService activeVpn = MapVpnService.Companion.getActiveInstance();
                if (activeVpn != null) {
                    activeVpn.logActiveRouteState();
                } else {
                    SstpConnectionLog.log("MapService", "VPN route state unavailable: VPN service instance is null");
                }
            }

            boolean initialCascadeCheck = checkCascadeThroughLocalProxies();
            updateRuntimeStatusAfterStart(sstpResult, initialCascadeCheck);
            startCascadeHealthChecks();
            
            // Update notification with actual status
            updateNotification();
            
        } catch (Exception e) {
            Timber.tag("MapService").e(e, "Failed to start proxy servers");
            SstpConnectionLog.logError("MapService", "Failed to start proxy servers", e);
            stopProxies();
            settings.setProxyRunning(false);
            settings.setRuntimeProxyMode(LocalSettings.PROXY_MODE_STOPPED);
            settings.setRuntimeSstpStatus(LocalSettings.STATUS_ERROR);
            settings.setRuntimeRemoteProxyStatus(LocalSettings.STATUS_ERROR);
            showErrorNotification("Ошибка запуска: " + e.getMessage());
        } finally {
            if (Thread.currentThread() == startupThread) {
                startupThread = null;
            }
        }
    }

    private boolean validateUpstreamReachable(
        List<ProxyNode> chain,
        String label,
        String pinnedRouteAddress,
        int pinnedRoutePort
    ) {
        if (chain == null || chain.isEmpty()) {
            return false;
        }
        ProxyNode upstream = chain.get(0);
        if (upstream == null) {
            return false;
        }

        String host = upstream.getHost();
        int port = upstream.getPort();
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            Timber.tag("MapService").w("%s upstream настроен некорректно", label);
            return false;
        }

        String probeHost = host;
        int probePort = port;
        if (pinnedRouteAddress != null
            && !pinnedRouteAddress.isEmpty()
            && pinnedRoutePort > 0
            && pinnedRoutePort == upstream.getPort()) {
            probeHost = pinnedRouteAddress;
            probePort = pinnedRoutePort;
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= UPSTREAM_CHECK_ATTEMPTS; attempt++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(probeHost, probePort), UPSTREAM_CHECK_TIMEOUT_MS);
                probe.setSoTimeout(UPSTREAM_CHECK_TIMEOUT_MS);
                verifyUpstreamProtocol(probe, upstream);
                Timber.tag("MapService").i(
                    "%s upstream check OK on attempt %d/%d: target=%s:%d probe=%s:%d route=%s:%d",
                    label,
                    attempt,
                    UPSTREAM_CHECK_ATTEMPTS,
                    host,
                    port,
                    probeHost,
                    probePort,
                    pinnedRouteAddress,
                    pinnedRoutePort
                );
                SstpConnectionLog.log(
                    "MapService",
                    label + " upstream check OK on attempt " + attempt + "/" + UPSTREAM_CHECK_ATTEMPTS
                        + " target=" + host + ":" + port
                        + " probe=" + probeHost + ":" + probePort
                        + " route=" + pinnedRouteAddress + ":" + pinnedRoutePort
                );
                return true;
            } catch (IOException e) {
                lastError = e;
                Timber.tag("MapService").w(
                    e,
                    "%s upstream check failed on attempt %d/%d: target=%s:%d probe=%s:%d route=%s:%d",
                    label,
                    attempt,
                    UPSTREAM_CHECK_ATTEMPTS,
                    host,
                    port,
                    probeHost,
                    probePort,
                    pinnedRouteAddress,
                    pinnedRoutePort
                );
                if (attempt < UPSTREAM_CHECK_ATTEMPTS) {
                    try {
                        Thread.sleep(UPSTREAM_CHECK_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        SstpConnectionLog.log("MapService", label + " upstream check interrupted");
                        break;
                    }
                }
            }
        }
        if (lastError != null) {
            SstpConnectionLog.logError("MapService", label + " upstream check failed", lastError);
        } else {
            SstpConnectionLog.log("MapService", label + " upstream check failed");
        }
        return false;
    }

    private void verifyUpstreamProtocol(Socket probe, ProxyNode upstream) throws IOException {
        if (upstream == null) {
            throw new IOException("Upstream node is missing");
        }
        if (upstream.getProtocol() == Protocol.SOCKS5) {
            verifySocks5Upstream(probe, upstream.getCredentials());
            return;
        }
        verifyHttpUpstream(probe, upstream.getCredentials());
    }

    private void verifySocks5Upstream(Socket socket, ProxyNode.ProxyCredentials credentials) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        if (credentials != null && credentials.isValid()) {
            out.write(new byte[] {0x05, 0x01, 0x02});
        } else {
            out.write(new byte[] {0x05, 0x01, 0x00});
        }
        out.flush();

        byte[] response = new byte[2];
        if (!readFully(in, response, 2)) {
            throw new IOException("SOCKS5 upstream check failed: no greeting response");
        }
        if (response[0] != 0x05) {
            throw new IOException("SOCKS5 upstream check failed: invalid version");
        }

        byte expectedMethod = credentials != null && credentials.isValid() ? (byte) 0x02 : (byte) 0x00;
        if (response[1] != expectedMethod) {
            throw new IOException("SOCKS5 upstream check failed: unexpected auth method " + (response[1] & 0xFF));
        }

        if (expectedMethod == 0x02) {
            out.write(credentials.getSocks5AuthPacket());
            out.flush();
            if (!readFully(in, response, 2)) {
                throw new IOException("SOCKS5 upstream check failed: no auth response");
            }
            if (response[0] != 0x01 || response[1] != 0x00) {
                throw new IOException("SOCKS5 upstream check failed: auth rejected");
            }
        }
    }

    private void verifyHttpUpstream(Socket socket, ProxyNode.ProxyCredentials credentials) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        StringBuilder request = new StringBuilder()
            .append("CONNECT 1.1.1.1:443 HTTP/1.1\r\n")
            .append("Host: 1.1.1.1:443\r\n")
            .append("Proxy-Connection: Keep-Alive\r\n");
        if (credentials != null && credentials.isValid()) {
            request.append("Proxy-Authorization: Basic ")
                .append(credentials.getBase64Encoded())
                .append("\r\n");
        }
        request.append("\r\n");

        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String statusLine = readLine(in);
        if (statusLine == null || !statusLine.startsWith("HTTP/1.")) {
            throw new IOException("HTTP upstream check failed: invalid status line");
        }

        int statusCode = parseHttpStatusCode(statusLine);
        if (statusCode == 200 || statusCode == 407 || statusCode == 403 || statusCode == 502) {
            consumeHttpHeaders(in);
            return;
        }
        throw new IOException("HTTP upstream check failed: " + statusLine);
    }

    private int parseHttpStatusCode(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("HTTP upstream check failed: malformed status line");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("HTTP upstream check failed: malformed status code", e);
        }
    }

    private void consumeHttpHeaders(InputStream in) throws IOException {
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                return;
            }
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = in.read();
            if (value == -1) {
                return line.length() == 0 ? null : line.toString();
            }
            if (value == '\n') {
                return line.toString().replace("\r", "");
            }
            line.append((char) value);
            if (line.length() > 8192) {
                throw new IOException("Upstream check failed: header line too long");
            }
        }
    }

    private void showErrorNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            Notification errorNotif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MAP Proxy")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
            manager.notify(NOTIFICATION_ID, errorNotif);
        }
    }

    /**
     * Local health check: verify SOCKS5 endpoint accepts a connection and handshake.
     */
    private void verifyLocalSocks5(String bindAddress, int port) throws IOException {
        String probeHost = resolveProbeHost(bindAddress);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(probeHost, port), LOCAL_SOCKS5_CHECK_TIMEOUT_MS);
            socket.setSoTimeout(LOCAL_SOCKS5_CHECK_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            ProxyNode.ProxyCredentials credentials = getLocalProxyCredentials();
            if (credentials != null) {
                out.write(new byte[]{0x05, 0x01, 0x02});
            } else {
                out.write(new byte[]{0x05, 0x01, 0x00});
            }
            out.flush();

            byte[] response = new byte[2];
            if (!readFully(in, response, 2)) {
                throw new IOException("SOCKS5 health check failed: no handshake response");
            }

            byte expectedAuthMethod = credentials != null ? (byte) 0x02 : (byte) 0x00;
            if (response[0] != 0x05 || response[1] != expectedAuthMethod) {
                throw new IOException("SOCKS5 health check failed: invalid handshake response");
            }

            if (credentials != null) {
                out.write(credentials.getSocks5AuthPacket());
                out.flush();
                if (!readFully(in, response, 2) || response[1] != 0x00) {
                    throw new IOException("SOCKS5 health check failed: auth rejected");
                }
            }

            Timber.tag("MapService").i("SOCKS5 local health check OK on %s:%d", probeHost, port);
        }
    }

    private String resolveProbeHost(String bindAddress) {
        if (bindAddress == null || bindAddress.isEmpty() || "0.0.0.0".equals(bindAddress)) {
            return "127.0.0.1";
        }
        return bindAddress;
    }

    private boolean readFully(InputStream in, byte[] buffer, int expected) throws IOException {
        int offset = 0;
        while (offset < expected) {
            int read = in.read(buffer, offset, expected - offset);
            if (read == -1) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    /**
     * Stop all proxy servers.
     */
    private void stopProxies() {
        cancelStartupIfRunning();
        stopCascadeHealthChecks();
        if (sstpController != null) {
            sstpController.disconnect();
        }
        try {
            Intent vpnIntent = new Intent(this, MapVpnService.class);
            vpnIntent.putExtra(MapVpnService.EXTRA_ACTION, MapVpnService.ACTION_STOP);
            startService(vpnIntent);
        } catch (Exception ignored) {
        }
        stopProxyServersOnly();
        settings.resetRuntimeState();
    }

    private void stopProxyServersOnly() {
        if (socks5Server != null) {
            socks5Server.stop();
            socks5Server = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    private Notification buildInitialNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MAP Proxy")
                .setContentText("Запуск прокси-сервера...")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        StringBuilder contentText = new StringBuilder("Режим: ");
        contentText.append(settings.getRuntimeProxyMode());
        contentText.append(" | ");
        if (settings.isSocks5Enabled()) {
            contentText.append("SOCKS5:").append(settings.getSocks5Port());
        }
        if (settings.isHttpEnabled()) {
            if (settings.isSocks5Enabled()) contentText.append(", ");
            contentText.append("HTTP:").append(settings.getHttpPort());
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MAP Proxy")
                .setContentText(contentText.toString())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private ProxyNode.ProxyCredentials getLocalProxyCredentials() {
        if (!settings.hasLocalProxyCredentials()) {
            return null;
        }
        return new ProxyNode.ProxyCredentials(
            settings.getLocalProxyUser(),
            settings.getLocalProxyPass()
        );
    }

    private List<ProxyNode> buildSocks5Chain() {
        if (!settings.isSocks5UpstreamEnabled()) {
            return null;
        }
        String host = settings.getSocks5UpstreamHost();
        if (host == null || host.isEmpty()) {
            return null;
        }

        ProxyNode.Builder builder = new ProxyNode.Builder()
            .host(host)
            .port(settings.getSocks5UpstreamPort());
        String protocol = settings.getSocks5UpstreamProtocol();
        builder.protocol("SOCKS5".equals(protocol) ? Protocol.SOCKS5 : Protocol.HTTP);

        String user = settings.getSocks5UpstreamUser();
        String pass = settings.getSocks5UpstreamPass();
        if (user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
            builder.credentials(user, pass);
        }

        List<ProxyNode> chain = new ArrayList<>();
        chain.add(builder.build());
        return chain;
    }

    private List<ProxyNode> buildHttpChain() {
        if (!settings.isHttpUpstreamEnabled()) {
            return null;
        }
        String host = settings.getHttpUpstreamHost();
        if (host == null || host.isEmpty()) {
            return null;
        }

        ProxyNode.Builder builder = new ProxyNode.Builder()
            .host(host)
            .port(settings.getHttpUpstreamPort());
        String protocol = settings.getHttpUpstreamProtocol();
        builder.protocol("SOCKS5".equals(protocol) ? Protocol.SOCKS5 : Protocol.HTTP);

        String user = settings.getHttpUpstreamUser();
        String pass = settings.getHttpUpstreamPass();
        if (user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
            builder.credentials(user, pass);
        }

        List<ProxyNode> chain = new ArrayList<>();
        chain.add(builder.build());
        return chain;
    }

    private void updateRuntimeStatusAfterStart(SstpController.Result sstpResult, boolean remoteCheckSuccessful) {
        settings.setProxyRunning(true);
        settings.setRuntimeProxyMode(LocalSettings.PROXY_MODE_CASCADE);
        SstpConnectionLog.log("MapService", "Local proxy mode forced to cascade");
        settings.setRuntimeRemoteProxyStatus(
            remoteCheckSuccessful ? LocalSettings.STATUS_CONNECTED : LocalSettings.STATUS_UNREACHABLE
        );
        if (!sstpResult.isTunnelReady()) {
            SstpConnectionLog.log("MapService", "SSTP tunnel is not ready, cascade checks will keep running");
        }
    }

    private void startCascadeHealthChecks() {
        stopCascadeHealthChecks();
        stopCascadeHealthChecks = false;
        cascadeHealthThread = new Thread(this::runCascadeHealthChecksLoop, "MAP-Cascade-Health");
        cascadeHealthThread.start();
    }

    private void stopCascadeHealthChecks() {
        stopCascadeHealthChecks = true;
        if (cascadeHealthThread != null && cascadeHealthThread.isAlive()) {
            cascadeHealthThread.interrupt();
        }
        cascadeHealthThread = null;
    }

    private void runCascadeHealthChecksLoop() {
        while (!stopCascadeHealthChecks) {
            boolean ok = checkCascadeThroughLocalProxies();
            settings.setRuntimeRemoteProxyStatus(ok ? LocalSettings.STATUS_CONNECTED : LocalSettings.STATUS_UNREACHABLE);
            updateNotification();
            int intervalSec = settings.getProxyHealthcheckIntervalSec();
            try {
                Thread.sleep(intervalSec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean checkCascadeThroughLocalProxies() {
        String probeHost = settings.getProxyHealthcheckHost();
        int probePort = settings.getProxyHealthcheckPort();
        // Backward compatibility for previously saved values like "host:443".
        int singleColonIndex = probeHost.lastIndexOf(':');
        if (singleColonIndex > 0 && probeHost.indexOf(':') == singleColonIndex) {
            String portCandidate = probeHost.substring(singleColonIndex + 1);
            try {
                int parsedPort = Integer.parseInt(portCandidate);
                if (parsedPort >= 1 && parsedPort <= 65535) {
                    probeHost = probeHost.substring(0, singleColonIndex).trim();
                    probePort = parsedPort;
                }
            } catch (NumberFormatException ignored) {
                // Keep configured host/port as-is when suffix is not a valid port.
            }
        }
        boolean checkedAny = false;
        boolean success = false;

        if (settings.isSocks5Enabled() && socks5Server != null) {
            checkedAny = true;
            CascadeCheckResult socksResult = checkCascadeViaLocalSocks5(probeHost, probePort);
            success = success || socksResult.ok;
            SstpConnectionLog.log(
                "MapService",
                "Cascade SOCKS5 check " + (socksResult.ok ? "OK" : "FAILED")
                    + " via local proxy to " + probeHost + ":" + probePort
                    + " detail=" + socksResult.detail
            );
        }

        if (settings.isHttpEnabled() && httpServer != null) {
            checkedAny = true;
            CascadeCheckResult httpResult = checkCascadeViaLocalHttp(probeHost, probePort);
            success = success || httpResult.ok;
            SstpConnectionLog.log(
                "MapService",
                "Cascade HTTP check " + (httpResult.ok ? "OK" : "FAILED")
                    + " via local proxy to " + probeHost + ":" + probePort
                    + " detail=" + httpResult.detail
            );
        }

        if (!checkedAny) {
            SstpConnectionLog.log("MapService", "Cascade checks skipped: no local proxy listeners are active");
            return false;
        }
        return success;
    }

    private CascadeCheckResult checkCascadeViaLocalSocks5(String targetHost, int targetPort) {
        String localHost = resolveProbeHost(settings.getSocks5BindAddress());
        ProxyNode.ProxyCredentials localCredentials = getLocalProxyCredentials();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(localHost, settings.getSocks5Port()), UPSTREAM_CHECK_TIMEOUT_MS);
            socket.setSoTimeout(UPSTREAM_CHECK_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            if (localCredentials != null && localCredentials.isValid()) {
                out.write(new byte[] {0x05, 0x01, 0x02});
            } else {
                out.write(new byte[] {0x05, 0x01, 0x00});
            }
            out.flush();

            byte[] response = new byte[2];
            if (!readFully(in, response, 2) || response[0] != 0x05) {
                return CascadeCheckResult.fail("SOCKS5 greeting failed");
            }
            byte expectedMethod = localCredentials != null && localCredentials.isValid() ? (byte) 0x02 : (byte) 0x00;
            if (response[1] != expectedMethod) {
                return CascadeCheckResult.fail("SOCKS5 auth method mismatch");
            }

            if (expectedMethod == 0x02) {
                out.write(localCredentials.getSocks5AuthPacket());
                out.flush();
                if (!readFully(in, response, 2) || response[0] != 0x01 || response[1] != 0x00) {
                    return CascadeCheckResult.fail("SOCKS5 local auth rejected");
                }
            }

            byte[] hostBytes = targetHost.getBytes(StandardCharsets.US_ASCII);
            if (hostBytes.length == 0 || hostBytes.length > 255) {
                return CascadeCheckResult.fail("invalid probe host");
            }
            byte[] request = new byte[7 + hostBytes.length];
            request[0] = 0x05;
            request[1] = 0x01;
            request[2] = 0x00;
            request[3] = 0x03;
            request[4] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, request, 5, hostBytes.length);
            request[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
            request[6 + hostBytes.length] = (byte) (targetPort & 0xFF);
            out.write(request);
            out.flush();

            byte[] head = new byte[4];
            if (!readFully(in, head, 4) || head[0] != 0x05 || head[1] != 0x00) {
                return CascadeCheckResult.fail("SOCKS5 CONNECT was rejected");
            }
            int atyp = head[3] & 0xFF;
            int addrLen = atyp == 0x01 ? 4 : atyp == 0x04 ? 16 : -1;
            if (atyp == 0x03) {
                int len = in.read();
                if (len < 0) {
                    return CascadeCheckResult.fail("SOCKS5 CONNECT reply truncated");
                }
                addrLen = len;
            }
            if (addrLen < 0) {
                return CascadeCheckResult.fail("SOCKS5 CONNECT reply address type unsupported");
            }
            byte[] tail = new byte[addrLen + 2];
            if (!readFully(in, tail, tail.length)) {
                return CascadeCheckResult.fail("SOCKS5 CONNECT reply tail truncated");
            }
            return CascadeCheckResult.ok("CONNECT 200 via " + localHost + ":" + settings.getSocks5Port());
        } catch (IOException e) {
            return CascadeCheckResult.fail(e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "I/O error" : e.getMessage()));
        }
    }

    private CascadeCheckResult checkCascadeViaLocalHttp(String targetHost, int targetPort) {
        String localHost = resolveProbeHost(settings.getHttpBindAddress());
        ProxyNode.ProxyCredentials localCredentials = getLocalProxyCredentials();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(localHost, settings.getHttpPort()), UPSTREAM_CHECK_TIMEOUT_MS);
            socket.setSoTimeout(UPSTREAM_CHECK_TIMEOUT_MS);

            StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(targetHost).append(":").append(targetPort).append(" HTTP/1.1\r\n")
                .append("Host: ").append(targetHost).append(":").append(targetPort).append("\r\n")
                .append("Proxy-Connection: Keep-Alive\r\n");
            if (localCredentials != null && localCredentials.isValid()) {
                request.append("Proxy-Authorization: Basic ")
                    .append(localCredentials.getBase64Encoded())
                    .append("\r\n");
            }
            request.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String statusLine = readLine(socket.getInputStream());
            if (statusLine == null || !statusLine.startsWith("HTTP/1.")) {
                return CascadeCheckResult.fail("HTTP CONNECT invalid status line");
            }
            int statusCode = parseHttpStatusCode(statusLine);
            consumeHttpHeaders(socket.getInputStream());
            if (statusCode == 200) {
                return CascadeCheckResult.ok("CONNECT 200 via " + localHost + ":" + settings.getHttpPort());
            }
            return CascadeCheckResult.fail("HTTP CONNECT status " + statusCode);
        } catch (IOException e) {
            return CascadeCheckResult.fail(e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "I/O error" : e.getMessage()));
        }
    }
}
