package com.map.sstp.protocol;

import com.map.sstp.SstpSessionConfig;
import com.map.sstp.ppp.PppSession;
import com.map.sstp.tls.SstpTlsSession;
import com.map.util.SstpConnectionLog;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Placeholder for SSTP control channel implementation.
 */
public class SstpControlChannel {
    private boolean started;
    private boolean connectAckReceived;
    private boolean callConnectedSent;
    private SstpPacketUtil.ControlHeader lastHeader;
    private SstpPacketUtil.CryptoBindingRequest cryptoBindingRequest;
    private SstpPacketUtil.CryptoBindingMaterial cryptoBindingMaterial;

    public void start(SstpSessionConfig config, SstpTlsSession session) throws IOException {
        if (config == null) {
            throw new IOException("Missing SSTP config");
        }
        if (session == null || session.getSocket() == null || session.getSocket().isClosed()) {
            throw new IOException("TLS session is not active");
        }

        OutputStream out = session.getOutputStream();

        byte[] request = SstpPacketUtil.buildCallConnectRequest();
        out.write(request);
        out.flush();
        SstpConnectionLog.log("SstpControlChannel", "Sent Call Connect Request");

        byte[] responsePacket = SstpPacketIo.readPacket(session.getInputStream());
        lastHeader = SstpPacketUtil.parseControlHeader(responsePacket);

        if (lastHeader.getVersion() != (SstpConstants.VERSION_1_0 & 0xFF)) {
            throw new IOException("Unsupported SSTP version in response");
        }
        if (lastHeader.getMessageType() != SstpConstants.MSG_CALL_CONNECT_ACK) {
            throw new IOException("Expected Call Connect Ack, got message type " + lastHeader.getMessageType());
        }

        cryptoBindingRequest = SstpPacketUtil.parseCryptoBindingRequest(responsePacket);
        connectAckReceived = true;
        SstpConnectionLog.log(
            "SstpControlChannel",
            "Received Call Connect Ack length=" + lastHeader.getLength() + " attrs=" + lastHeader.getAttributeCount()
        );
    }

    public void completeCallConnected(SstpTlsSession session, PppSession pppSession) throws IOException {
        ensureConnectAckReceived();
        if (session == null || session.getSocket() == null || session.getSocket().isClosed()) {
            throw new IOException("TLS session is not active");
        }

        cryptoBindingMaterial = SstpCryptoBinding.createMaterial(
            cryptoBindingRequest,
            session,
            pppSession
        );

        byte[] callConnected = SstpPacketUtil.buildCallConnected(cryptoBindingMaterial);
        OutputStream out = session.getOutputStream();
        out.write(callConnected);
        out.flush();
        started = true;
        callConnectedSent = true;
        SstpConnectionLog.log("SstpControlChannel", "Sent Call Connected");
    }

    public boolean handleControlPacket(SstpTlsSession session, byte[] packet) throws IOException {
        if (packet == null || packet.length < SstpConstants.CONTROL_HEADER_SIZE) {
            throw new IOException("SSTP control packet is too short");
        }
        if (session == null || session.getSocket() == null || session.getSocket().isClosed()) {
            throw new IOException("TLS session is not active");
        }

        SstpPacketUtil.ControlHeader header = SstpPacketUtil.parseControlHeader(packet);
        lastHeader = header;

        switch (header.getMessageType()) {
            case SstpConstants.MSG_ECHO_REQUEST:
                SstpConnectionLog.log("SstpControlChannel", "Received SSTP Echo Request");
                session.getOutputStream().write(SstpPacketUtil.buildControlPacket(SstpConstants.MSG_ECHO_RESPONSE));
                session.getOutputStream().flush();
                return true;
            case SstpConstants.MSG_ECHO_RESPONSE:
                SstpConnectionLog.log("SstpControlChannel", "Received SSTP Echo Response");
                return true;
            case SstpConstants.MSG_CALL_DISCONNECT:
                SstpConnectionLog.log("SstpControlChannel", "Received SSTP Call Disconnect");
                session.getOutputStream().write(
                    SstpPacketUtil.buildControlPacket(SstpConstants.MSG_CALL_DISCONNECT_ACK)
                );
                session.getOutputStream().flush();
                throw new IOException("SSTP server requested disconnect");
            case SstpConstants.MSG_CALL_ABORT:
                SstpConnectionLog.log("SstpControlChannel", "Received SSTP Call Abort");
                throw new IOException("SSTP server aborted the call");
            default:
                return false;
        }
    }

    public void stop() {
        started = false;
        connectAckReceived = false;
        callConnectedSent = false;
        lastHeader = null;
        cryptoBindingRequest = null;
        cryptoBindingMaterial = null;
    }

    public boolean isStarted() {
        return started;
    }

    public SstpPacketUtil.ControlHeader getLastHeader() {
        return lastHeader;
    }

    public boolean isConnectAckReceived() {
        return connectAckReceived;
    }

    public boolean isCallConnectedSent() {
        return callConnectedSent;
    }

    public SstpPacketUtil.CryptoBindingRequest getCryptoBindingRequest() {
        return cryptoBindingRequest;
    }

    public SstpPacketUtil.CryptoBindingMaterial getCryptoBindingMaterial() {
        return cryptoBindingMaterial;
    }

    private void ensureConnectAckReceived() throws IOException {
        if (!connectAckReceived || cryptoBindingRequest == null) {
            throw new IOException("SSTP Call Connect Ack was not received");
        }
    }
}
