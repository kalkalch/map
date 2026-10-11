package com.map.sstp;

import android.net.VpnService;
import com.map.service.MapVpnService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.map.sstp.io.TunnelRelay;
import com.map.sstp.ppp.PppSession;
import com.map.sstp.protocol.SstpControlChannel;
import com.map.sstp.protocol.SstpHttpsSession;
import com.map.sstp.protocol.SstpPacketIo;
import com.map.sstp.protocol.SstpPacketUtil;
import com.map.sstp.tls.SstpTlsClient;
import com.map.sstp.tls.SstpTlsSession;
import com.map.util.LocalSettings;
import com.map.util.SstpConnectionLog;
import timber.log.Timber;

/**
 * Android-oriented SSTP transport shell.
 * The actual SSTP/PPP implementation is intentionally isolated here so the
 * rest of the app already has a stable integration boundary.
 */
public class AndroidSstpTransport implements SstpTransport {
    private static final String TAG = "AndroidSstpTransport";
    private static final int MAX_PPP_NEGOTIATION_STEPS = 24;
    private static final long PPP_NEGOTIATION_TIMEOUT_MS = 45000L;
    private static final long RELAY_STABILITY_WINDOW_MS = 1200L;
    private static final long RELAY_STABILITY_POLL_MS = 100L;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final SstpTlsClient tlsClient = new SstpTlsClient();
    private final SstpHttpsSession httpsSession = new SstpHttpsSession();
    private final SstpControlChannel controlChannel = new SstpControlChannel();
    private final PppSession pppSession = new PppSession();
    private final TunnelRelay tunnelRelay = new TunnelRelay();
    private volatile SstpSessionConfig activeConfig;
    private volatile Stage stage = Stage.IDLE;
    private volatile SstpTunnelContext tunnelContext;
    private volatile String lastDetail;

    @Override
    public SstpController.Result connect(SstpSessionConfig config, VpnService vpnService) {
        disconnect();
        cancelled.set(false);
        activeConfig = config;
        stage = Stage.VALIDATING;
        lastDetail = null;

        if (vpnService == null) {
            return failure("VPN service отсутствует", Stage.VALIDATING, LocalSettings.STATUS_ERROR);
        }
        if (config == null) {
            return failure("SSTP config отсутствует", Stage.VALIDATING, LocalSettings.STATUS_ERROR);
        }
        if (config.getServerHost() == null || config.getServerHost().isEmpty()) {
            return failure("SSTP host отсутствует", Stage.VALIDATING, LocalSettings.STATUS_ERROR);
        }
        if (config.getRouteAddress() == null || config.getRouteAddress().isEmpty()) {
            return failure("Route to remote proxy отсутствует", Stage.VALIDATING, LocalSettings.STATUS_ERROR);
        }

        try {
            stage = Stage.CONNECTING_TLS;
            SstpConnectionLog.log(TAG, "Opening TLS session");
            SstpTlsSession tlsSession = tlsClient.connect(config, vpnService);

            stage = Stage.ESTABLISHING_HTTPS;
            SstpConnectionLog.log(TAG, "Starting HTTPS SSTP establish handshake");
            httpsSession.establish(config, tlsSession);

            stage = Stage.NEGOTIATING_SSTP;
            SstpConnectionLog.log(TAG, "Starting SSTP control negotiation");
            controlChannel.start(config, tlsSession);

            stage = Stage.NEGOTIATING_PPP;
            SstpConnectionLog.log(TAG, "Starting PPP negotiation");
            pppSession.negotiate(config);
            runPppNegotiation(tlsSession);
            if (!pppSession.isReadyForTunnel()) {
                return failure(
                    "PPP negotiation did not reach tunnel-ready state",
                    Stage.NEGOTIATING_PPP,
                    LocalSettings.STATUS_ERROR
                );
            }
            SstpConnectionLog.log(
                TAG,
                "PPP assigned IPv4: " + formatIpv4Address(pppSession.getAssignedIpv4Address())
            );
            SstpConnectionLog.log(
                TAG,
                "PPP peer IPv4: " + formatIpv4Address(pppSession.getPeerIpv4Address())
            );

            stage = Stage.PREPARING_TUN;
            String tunLocalAddress = resolveTunLocalIpv4Address(pppSession);
            SstpConnectionLog.log(TAG, "Preparing TUN context from PPP local IPv4: " + tunLocalAddress);
            tunnelContext = acquireTunnelContext(vpnService, tunLocalAddress);

            stage = Stage.FINALIZING_SSTP;
            SstpConnectionLog.log(TAG, "Sending SSTP Call Connected");
            controlChannel.completeCallConnected(tlsSession, pppSession);

            stage = Stage.STARTING_RELAY;
            SstpConnectionLog.log(TAG, "Starting tunnel relay");
            tunnelRelay.start(tunnelContext, tlsSession, pppSession);
            ensureRelayStableAfterStart();
            if (vpnService instanceof MapVpnService) {
                ((MapVpnService) vpnService).logTunnelMtuAfterSstpReady();
            }

            connected.set(true);
            lastDetail = "SSTP tunnel is connected";
            Timber.tag(TAG).i(
                "SSTP transport connected for host=%s port=%d route=%s/%d",
                config.getServerHost(),
                config.getServerPort(),
                config.getRouteAddress(),
                config.getRoutePrefix()
            );
            return new SstpController.Result(
                LocalSettings.STATUS_CONNECTED,
                true,
                lastDetail
            );
        } catch (IOException e) {
            cleanupPartialConnection();
            String detail = formatIOExceptionDetail(e, stage);
            return failure(detail, stage, classifyFailureStatus(e, stage, detail));
        } catch (Exception e) {
            cleanupPartialConnection();
            String detail = "Unexpected SSTP transport error: " + e.getMessage();
            return failure(detail, stage, LocalSettings.STATUS_ERROR);
        }
    }

    @Override
    public void disconnect() {
        cancelled.set(true);
        connected.set(false);
        stage = Stage.STOPPING;
        tunnelRelay.stop();
        pppSession.stop();
        controlChannel.stop();
        httpsSession.stop();
        tlsClient.disconnect();
        if (tunnelContext != null) {
            try {
                tunnelContext.close();
            } catch (IOException ignored) {
            } finally {
                tunnelContext = null;
            }
        }
        activeConfig = null;
        stage = Stage.IDLE;
        lastDetail = null;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public SstpSessionConfig getActiveConfig() {
        return activeConfig;
    }

    public Stage getStage() {
        return stage;
    }

    public String getLastDetail() {
        return lastDetail;
    }

    private void cleanupPartialConnection() {
        tunnelRelay.stop();
        pppSession.stop();
        controlChannel.stop();
        httpsSession.stop();
        tlsClient.disconnect();
        if (tunnelContext != null) {
            try {
                tunnelContext.close();
            } catch (IOException ignored) {
            } finally {
                tunnelContext = null;
            }
        }
    }

    private SstpTunnelContext acquireTunnelContext(VpnService vpnService, String pppAssignedAddress) throws IOException {
        if (!(vpnService instanceof MapVpnService)) {
            throw new IOException("VPN service type is unsupported");
        }
        MapVpnService mapVpnService = (MapVpnService) vpnService;
        mapVpnService.establishTunnelForPppAddress(pppAssignedAddress);
        android.os.ParcelFileDescriptor tunnelInterface = mapVpnService.duplicateTunnelInterface();
        if (tunnelInterface == null) {
            throw new IOException("TUN interface is not established");
        }
        return new SstpTunnelContext(tunnelInterface);
    }

    private SstpController.Result failure(String detail, Stage failedStage, String status) {
        connected.set(false);
        lastDetail = "[" + failedStage.name() + "] " + detail;
        Timber.tag(TAG).w("SSTP stage %s failed: %s", failedStage, detail);
        SstpConnectionLog.log(TAG, "Stage " + failedStage.name() + " failed: " + detail);
        return new SstpController.Result(
            status,
            false,
            lastDetail
        );
    }

    private String classifyFailureStatus(IOException error, Stage failedStage, String detail) {
        if (failedStage == Stage.CONNECTING_TLS) {
            if (error instanceof UnknownHostException
                || error instanceof SocketTimeoutException
                || error instanceof ConnectException) {
                return LocalSettings.STATUS_UNREACHABLE;
            }
        }
        if (detail != null && detail.toLowerCase().contains("unsupported")) {
            return LocalSettings.STATUS_UNSUPPORTED;
        }
        return LocalSettings.STATUS_ERROR;
    }

    private void runPppNegotiation(SstpTlsSession tlsSession) throws IOException {
        if (tlsSession == null) {
            throw new IOException("TLS session is not available for PPP negotiation");
        }

        OutputStream out = tlsSession.getOutputStream();
        InputStream in = tlsSession.getInputStream();

        sendPppFrame(out, pppSession.createInitialLcpConfigureRequest());
        boolean ipcpRequestSent = false;
        long deadlineMs = System.currentTimeMillis() + PPP_NEGOTIATION_TIMEOUT_MS;

        int step = 0;
        while (step < MAX_PPP_NEGOTIATION_STEPS && !cancelled.get()) {
            if (System.currentTimeMillis() >= deadlineMs) {
                throw new IOException("PPP negotiation timed out before tunnel-ready state");
            }
            try {
                byte[] packet = SstpPacketIo.readPacket(in);
                if (SstpPacketIo.isControlPacket(packet)) {
                    if (controlChannel.handleControlPacket(tlsSession, packet)) {
                        continue;
                    }
                    throw new IOException("Unexpected SSTP control packet during PPP negotiation");
                }

                SstpPacketUtil.DataPacket dataPacket = SstpPacketUtil.parseDataPacket(packet);
                boolean wasChapAuthenticated = pppSession.isChapAuthenticated();
                byte[] response = pppSession.handleIncoming(dataPacket.getPayload());
                if (response != null) {
                    sendPppFrame(out, response);
                }
                if (!wasChapAuthenticated && pppSession.isChapAuthenticated() && !ipcpRequestSent) {
                    sendPppFrame(out, pppSession.createIpcpConfigureRequest());
                    ipcpRequestSent = true;
                }
                step++;
                if (pppSession.isReadyForTunnel()) {
                    Timber.tag(TAG).i("PPP negotiation reached tunnel-ready state in %d steps", step);
                    SstpConnectionLog.log(TAG, "PPP negotiation reached tunnel-ready state");
                    return;
                }
            } catch (SocketTimeoutException e) {
                // Mobile networks may pause briefly; keep waiting until deadline.
            }
        }

        if (cancelled.get()) {
            throw new IOException("PPP negotiation cancelled");
        }
        throw new IOException("PPP negotiation timed out before tunnel-ready state");
    }

    private void sendPppFrame(OutputStream out, byte[] pppFrame) throws IOException {
        if (out == null) {
            throw new IOException("TLS output stream is not available");
        }
        byte[] sstpPacket = SstpPacketUtil.buildDataPacket(pppFrame);
        out.write(sstpPacket);
        out.flush();
    }

    private String formatIOExceptionDetail(IOException e, Stage failedStage) {
        if (e == null) {
            return "I/O error";
        }
        if (failedStage == Stage.CONNECTING_TLS) {
            if (e instanceof UnknownHostException) {
                return "Не удалось разрешить DNS имя SSTP сервера";
            }
            if (e instanceof SocketTimeoutException) {
                return "Таймаут подключения к SSTP серверу";
            }
            if (e instanceof ConnectException) {
                return "SSTP сервер недоступен (TCP connect failed)";
            }
        }
        String message = e.getMessage();
        return message != null && !message.isEmpty()
            ? message
            : e.getClass().getSimpleName();
    }

    private void ensureRelayStableAfterStart() throws IOException {
        long deadline = System.currentTimeMillis() + RELAY_STABILITY_WINDOW_MS;
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get()) {
                throw new IOException("SSTP connection cancelled");
            }
            if (!tunnelRelay.isRunning()) {
                String reason = tunnelRelay.getLastStopReason();
                if (reason == null || reason.isEmpty()) {
                    reason = "unknown reason";
                }
                throw new IOException("Tunnel relay stopped shortly after start: " + reason);
            }
            try {
                Thread.sleep(RELAY_STABILITY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled.set(true);
                throw new IOException("Interrupted while waiting for tunnel relay stability", e);
            }
        }
    }

    private String formatIpv4Address(byte[] address) {
        if (address == null || address.length != 4) {
            return "n/a";
        }
        return (address[0] & 0xFF)
            + "."
            + (address[1] & 0xFF)
            + "."
            + (address[2] & 0xFF)
            + "."
            + (address[3] & 0xFF);
    }

    private String resolveTunLocalIpv4Address(PppSession pppSession) throws IOException {
        byte[] assigned = pppSession != null ? pppSession.getAssignedIpv4Address() : null;
        if (isValidNonZeroIpv4(assigned)) {
            return formatIpv4Address(assigned);
        }
        throw new IOException("PPP local IPv4 address is not negotiated");
    }

    private boolean isValidNonZeroIpv4(byte[] address) {
        if (address == null || address.length != 4) {
            return false;
        }
        return (address[0] | address[1] | address[2] | address[3]) != 0;
    }

    public enum Stage {
        IDLE,
        VALIDATING,
        PREPARING_TUN,
        CONNECTING_TLS,
        ESTABLISHING_HTTPS,
        NEGOTIATING_SSTP,
        NEGOTIATING_PPP,
        FINALIZING_SSTP,
        STARTING_RELAY,
        STOPPING
    }
}
