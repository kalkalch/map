package com.map.sstp;

import android.net.VpnService;
import com.map.util.LocalSettings;
import com.map.util.SstpConnectionLog;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Integration point for a future SSTP engine.
 * This project currently has no native SSTP transport implementation,
 * but the rest of the app can already react to its runtime state.
 */
public class SstpController {
    private final SstpTransport transport;
    private volatile boolean cancelled;

    public SstpController() {
        this(new AndroidSstpTransport());
    }

    public SstpController(SstpTransport transport) {
        this.transport = transport;
    }

    public Result connect(LocalSettings settings, VpnService vpnService, StatusListener listener) {
        cancelled = false;
        if (!settings.isSstpEnabled()) {
            SstpConnectionLog.log("SstpController", "SSTP is disabled in settings");
            return new Result(LocalSettings.STATUS_DISABLED, false, "SSTP отключен");
        }
        if (settings.getSstpHost().isEmpty()) {
            SstpConnectionLog.log("SstpController", "SSTP host is missing");
            return new Result(LocalSettings.STATUS_ERROR, false, "Не задан адрес SSTP");
        }
        if (!settings.isValidPort(settings.getSstpPort())) {
            SstpConnectionLog.log("SstpController", "SSTP port is invalid");
            return new Result(LocalSettings.STATUS_ERROR, false, "Неверный порт SSTP");
        }
        if (transport.isAvailable() && vpnService == null) {
            SstpConnectionLog.log("SstpController", "VPN service is not prepared");
            return new Result(LocalSettings.STATUS_ERROR, false, "VPN service не подготовлен");
        }

        SstpRoutePlanner.Plan routePlan;
        try {
            routePlan = SstpRoutePlanner.plan(settings);
        } catch (UnknownHostException e) {
            SstpConnectionLog.logError("SstpController", "Failed to resolve remote proxy route", e);
            return new Result(LocalSettings.STATUS_ERROR, false, "Не удалось разрешить адрес remote proxy");
        }
        if (!routePlan.isValid()) {
            SstpConnectionLog.log("SstpController", "Route plan is invalid: " + routePlan.getDetail());
            return new Result(LocalSettings.STATUS_ERROR, false, routePlan.getDetail());
        }
        List<String> targets = new ArrayList<>();
        for (SstpRoutePlanner.ProxyEndpoint remoteProxy : routePlan.getRemoteProxies()) {
            targets.add(remoteProxy.getHost() + ":" + remoteProxy.getPort());
        }
        List<String> routes = new ArrayList<>();
        for (SstpRoutePlanner.RouteEntry route : routePlan.getRoutes()) {
            routes.add(route.getRouteAddress() + "/" + route.getRoutePrefix());
        }
        SstpConnectionLog.log(
            "SstpController",
            "Route plan prepared: targets=" + String.join(", ", targets)
                + " routes=" + String.join(", ", routes)
        );

        SstpSessionConfig sessionConfig = new SstpSessionConfig(
            settings.getSstpHost(),
            settings.getSstpPort(),
            settings.getSstpUser(),
            settings.getSstpPass(),
            settings.isSstpIgnoreCertErrors(),
            routePlan.getRouteAddress(),
            routePlan.getRoutePrefix(),
            "MAP-SSTP"
        );

        int maxAttempts = settings.isSstpReconnectEnabled() ? settings.getSstpReconnectAttempts() : 1;
        int delaySec = settings.getSstpReconnectDelaySec();
        Result lastResult = null;

        for (int attempt = 1; attempt <= maxAttempts && !cancelled; attempt++) {
            if (listener != null) {
                listener.onStatusChanged(
                    attempt == 1 ? LocalSettings.STATUS_CONNECTING : LocalSettings.STATUS_RECONNECTING,
                    "Попытка SSTP " + attempt + " из " + maxAttempts
                );
            }
            SstpConnectionLog.log("SstpController", "Starting SSTP attempt " + attempt + " of " + maxAttempts);

            long attemptStartedAt = System.currentTimeMillis();
            Result transportResult = transport.connect(sessionConfig, vpnService);
            lastResult = new Result(
                transportResult.getStatus(),
                transportResult.isTunnelReady(),
                transportResult.getDetail(),
                routePlan.getRouteAddress(),
                routePlan.getRemoteProxy() != null ? routePlan.getRemoteProxy().getPort() : -1
            );
            long elapsedMs = System.currentTimeMillis() - attemptStartedAt;
            if (lastResult.isTunnelReady()) {
                SstpConnectionLog.log(
                    "SstpController",
                    "SSTP transport reported tunnel ready in " + elapsedMs + " ms"
                );
                return lastResult;
            }
            SstpConnectionLog.log(
                "SstpController",
                "SSTP attempt failed in " + elapsedMs + " ms with status=" + lastResult.getStatus()
                    + " detail=" + lastResult.getDetail()
            );
            if (attempt >= maxAttempts || cancelled) {
                break;
            }
            sleepBeforeRetry(delaySec, listener, attempt + 1);
        }

        return lastResult != null
            ? lastResult
            : new Result(LocalSettings.STATUS_ERROR, false, "SSTP остановлен");
    }

    public void disconnect() {
        cancelled = true;
        transport.disconnect();
    }

    private void sleepBeforeRetry(int delaySec, StatusListener listener, int nextAttempt) {
        if (listener != null) {
            listener.onStatusChanged(
                LocalSettings.STATUS_RECONNECTING,
                "Повторное подключение SSTP через " + delaySec + " сек, попытка " + nextAttempt
            );
        }
        SstpConnectionLog.log("SstpController", "Waiting before reconnect: " + delaySec + " sec");
        try {
            Thread.sleep(delaySec * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }
    }

    public interface StatusListener {
        void onStatusChanged(String status, String detail);
    }

    public static final class Result {
        private final String status;
        private final boolean tunnelReady;
        private final String detail;
        private final String resolvedRouteAddress;
        private final int resolvedRoutePort;

        public Result(String status, boolean tunnelReady, String detail) {
            this(status, tunnelReady, detail, null, -1);
        }

        public Result(
            String status,
            boolean tunnelReady,
            String detail,
            String resolvedRouteAddress,
            int resolvedRoutePort
        ) {
            this.status = status;
            this.tunnelReady = tunnelReady;
            this.detail = detail;
            this.resolvedRouteAddress = resolvedRouteAddress;
            this.resolvedRoutePort = resolvedRoutePort;
        }

        public String getStatus() {
            return status;
        }

        public boolean isTunnelReady() {
            return tunnelReady;
        }

        public String getDetail() {
            return detail;
        }

        public String getResolvedRouteAddress() {
            return resolvedRouteAddress;
        }

        public int getResolvedRoutePort() {
            return resolvedRoutePort;
        }
    }
}
