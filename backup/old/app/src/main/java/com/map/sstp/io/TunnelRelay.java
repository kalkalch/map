package com.map.sstp.io;

import com.map.sstp.SstpTunnelContext;
import com.map.sstp.ppp.PppConstants;
import com.map.sstp.ppp.PppPacketUtil;
import com.map.sstp.ppp.PppSession;
import com.map.sstp.protocol.SstpControlChannel;
import com.map.sstp.protocol.SstpConstants;
import com.map.sstp.protocol.SstpPacketIo;
import com.map.sstp.protocol.SstpPacketUtil;
import com.map.sstp.tls.SstpTlsSession;
import com.map.util.SstpConnectionLog;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import timber.log.Timber;

/**
 * TUN <-> SSTP data relay loops.
 */
public class TunnelRelay {
    private static final String TAG = "TunnelRelay";
    private static final int BUFFER_SIZE = 4096;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final SstpControlChannel controlChannel = new SstpControlChannel();
    private Thread tunToTlsThread;
    private Thread tlsToTunThread;
    private SstpTunnelContext activeContext;
    private SstpTlsSession activeSession;
    private PppSession activePppSession;
    private volatile String lastStopReason;

    public void start(SstpTunnelContext context, SstpTlsSession session, PppSession pppSession) throws IOException {
        if (context == null || !context.hasTunnelInterface()) {
            throw new IOException("TUN interface is not ready");
        }
        if (session == null || session.getSocket() == null || session.getSocket().isClosed()) {
            throw new IOException("TLS session is not ready");
        }
        if (pppSession == null || !pppSession.isNegotiated()) {
            throw new IOException("PPP session is not ready");
        }
        stop();

        activeContext = context;
        activeSession = session;
        activePppSession = pppSession;
        lastStopReason = null;
        running.set(true);
        SstpConnectionLog.log(TAG, "Tunnel relay started");

        FileInputStream tunIn = context.getInputStream();
        FileOutputStream tunOut = context.getOutputStream();
        InputStream tlsIn = session.getInputStream();
        OutputStream tlsOut = session.getOutputStream();

        tunToTlsThread = new Thread(
            () -> runTunToTlsLoop(tunIn, tlsOut),
            "MAP-SSTP-TUN->TLS"
        );
        tlsToTunThread = new Thread(
            () -> runTlsToTunLoop(tlsIn, tunOut),
            "MAP-SSTP-TLS->TUN"
        );

        tunToTlsThread.start();
        tlsToTunThread.start();
    }

    public void stop() {
        running.set(false);
        interruptThread(tunToTlsThread);
        interruptThread(tlsToTunThread);
        tunToTlsThread = null;
        tlsToTunThread = null;
        activeContext = null;
        activeSession = null;
        activePppSession = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runTunToTlsLoop(FileInputStream tunIn, OutputStream tlsOut) {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (running.get()) {
            try {
                int read = tunIn.read(buffer);
                if (read < 0) {
                    Timber.tag(TAG).i("TUN -> TLS relay reached EOF");
                    SstpConnectionLog.log(TAG, "TUN -> TLS relay reached EOF");
                    lastStopReason = "TUN -> TLS EOF";
                    stop();
                    return;
                }
                if (read == 0) {
                    continue;
                }

                byte[] payload = new byte[read];
                System.arraycopy(buffer, 0, payload, 0, read);
                byte[] pppFrame = PppPacketUtil.buildDataFrameForIpPacket(payload);
                if (pppFrame.length > SstpConstants.MAX_DATA_PAYLOAD_LENGTH) {
                    throw new IOException(
                        "PPP frame is too large for SSTP data packet: " + pppFrame.length
                            + " (max " + SstpConstants.MAX_DATA_PAYLOAD_LENGTH + ")"
                    );
                }
                byte[] packet = SstpPacketUtil.buildDataPacket(pppFrame);
                synchronized (tlsOut) {
                    tlsOut.write(packet);
                    tlsOut.flush();
                }
            } catch (IOException e) {
                if (running.get()) {
                    Timber.tag(TAG).w(e, "TUN -> TLS relay failed");
                    SstpConnectionLog.logError(TAG, "TUN -> TLS relay failed", e);
                    lastStopReason = "TUN -> TLS failed: " + e.getMessage();
                }
                stop();
                return;
            }
        }
    }

    private void runTlsToTunLoop(InputStream tlsIn, FileOutputStream tunOut) {
        Set<Integer> protocolRejectSent = new HashSet<>();
        while (running.get()) {
            try {
                byte[] packet = SstpPacketIo.readPacket(tlsIn);
                if (SstpPacketIo.isControlPacket(packet)) {
                    if (activeSession != null && controlChannel.handleControlPacket(activeSession, packet)) {
                        continue;
                    }
                    Timber.tag(TAG).w("Ignoring unsupported SSTP control packet during relay");
                    continue;
                }

                SstpPacketUtil.DataPacket dataPacket = SstpPacketUtil.parseDataPacket(packet);
                byte[] payload = dataPacket.getPayload();
                if (payload.length == 0) {
                    continue;
                }

                PppPacketUtil.DataFrame frame = PppPacketUtil.parseDataFrame(payload);
                if (isPppControlProtocol(frame.getProtocol())) {
                    if (activePppSession == null) {
                        SstpConnectionLog.log(TAG, "PPP session is unavailable during relay control handling");
                        continue;
                    }
                    byte[] response = activePppSession.handleIncoming(payload);
                    if (response != null) {
                        writePppFrameToTls(response);
                    }
                    continue;
                }
                if (frame.getProtocol() != PppConstants.PROTOCOL_IPV4
                    && frame.getProtocol() != PppConstants.PROTOCOL_IPV6) {
                    maybeSendProtocolReject(frame, protocolRejectSent);
                    SstpConnectionLog.log(
                        TAG,
                        "Ignoring unsupported PPP data frame protocol=0x" + Integer.toHexString(frame.getProtocol())
                    );
                    continue;
                }

                tunOut.write(frame.getPayload());
                tunOut.flush();
            } catch (SocketTimeoutException e) {
                // Allow periodic wakeups so stop() can interrupt a blocked read.
            } catch (IOException e) {
                if (running.get()) {
                    Timber.tag(TAG).w(e, "TLS -> TUN relay failed");
                    SstpConnectionLog.logError(TAG, "TLS -> TUN relay failed", e);
                    lastStopReason = "TLS -> TUN failed: " + e.getMessage();
                }
                stop();
                return;
            }
        }
    }

    private void interruptThread(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private boolean isPppControlProtocol(int protocol) {
        return protocol == PppConstants.PROTOCOL_LCP
            || protocol == PppConstants.PROTOCOL_CHAP
            || protocol == PppConstants.PROTOCOL_IPCP
            || PppConstants.isNetworkControlProtocol(protocol);
    }

    private void writePppFrameToTls(byte[] pppFrame) throws IOException {
        if (pppFrame == null || pppFrame.length == 0) {
            return;
        }
        if (activeSession == null) {
            throw new IOException("TLS session is not active");
        }
        OutputStream tlsOut = activeSession.getOutputStream();
        byte[] sstpPacket = SstpPacketUtil.buildDataPacket(pppFrame);
        synchronized (tlsOut) {
            tlsOut.write(sstpPacket);
            tlsOut.flush();
        }
    }

    private void maybeSendProtocolReject(PppPacketUtil.DataFrame frame, Set<Integer> protocolRejectSent) {
        if (frame == null || activePppSession == null || protocolRejectSent == null) {
            return;
        }
        int protocol = frame.getProtocol();
        if (protocol == PppConstants.PROTOCOL_IPV4 || protocolRejectSent.contains(protocol)) {
            return;
        }
        try {
            byte[] reject = activePppSession.createLcpProtocolReject(protocol, frame.getPayload());
            writePppFrameToTls(reject);
            protocolRejectSent.add(protocol);
            SstpConnectionLog.log(
                TAG,
                "Sent LCP Protocol-Reject for PPP protocol=0x" + Integer.toHexString(protocol)
            );
        } catch (IOException e) {
            SstpConnectionLog.logError(
                TAG,
                "Failed to send LCP Protocol-Reject for protocol=0x" + Integer.toHexString(protocol),
                e
            );
        }
    }

    public String getLastStopReason() {
        return lastStopReason;
    }
}
