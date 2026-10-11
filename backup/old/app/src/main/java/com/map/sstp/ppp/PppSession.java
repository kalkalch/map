package com.map.sstp.ppp;

import com.map.sstp.SstpSessionConfig;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Placeholder for PPP negotiation over SSTP.
 */
public class PppSession {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final byte[] INITIAL_LCP_OPTIONS = new byte[0];
    private static final byte[] REQUIRED_LCP_AUTH_OPTION = new byte[] {
        (byte) PppConstants.OPTION_AUTHENTICATION_PROTOCOL,
        0x05,
        (byte) ((PppConstants.PROTOCOL_CHAP >> 8) & 0xFF),
        (byte) (PppConstants.PROTOCOL_CHAP & 0xFF),
        (byte) PppConstants.CHAP_ALGORITHM_MSCHAPV2
    };
    private static final byte[] IPCP_IP_ADDRESS_OPTION = new byte[] {
        (byte) PppConstants.OPTION_IP_ADDRESS,
        0x06,
        0x00,
        0x00,
        0x00,
        0x00
    };

    private boolean negotiated;
    private boolean lcpPeerRequestAcknowledged;
    private boolean lcpClientRequestAcknowledged;
    private boolean chapResponseSent;
    private boolean chapAuthenticated;
    private boolean ipcpPeerRequestAcknowledged;
    private boolean ipcpClientRequestAcknowledged;
    private byte[] higherLayerAuthKey = zeroedHlak();
    private String username;
    private String password;
    private MsChapV2Material msChapV2Material;
    private byte[] pendingPeerChallenge;
    private byte[] pendingAuthenticatorChallenge;
    private String pendingAuthenticatorResponse;
    private int lastChapIdentifier = -1;
    private int nextIdentifier = 1;
    private int lastClientLcpRequestIdentifier = -1;
    private int lastClientIpcpRequestIdentifier = -1;
    private byte[] currentLcpOptions = INITIAL_LCP_OPTIONS.clone();
    private byte[] currentIpcpOptions = IPCP_IP_ADDRESS_OPTION.clone();
    private byte[] assignedIpv4Address = new byte[] {0, 0, 0, 0};
    private byte[] peerIpv4Address = new byte[] {0, 0, 0, 0};

    public void negotiate(SstpSessionConfig config) throws IOException {
        if (config == null) {
            throw new IOException("Missing PPP config");
        }
        if (config.getUsername() == null || config.getUsername().isEmpty()) {
            throw new IOException("PPP username is required");
        }
        if (config.getPassword() == null || config.getPassword().isEmpty()) {
            throw new IOException("PPP password is required");
        }
        negotiated = true;
        lcpPeerRequestAcknowledged = false;
        lcpClientRequestAcknowledged = false;
        chapResponseSent = false;
        chapAuthenticated = false;
        ipcpPeerRequestAcknowledged = false;
        ipcpClientRequestAcknowledged = false;
        username = config.getUsername();
        password = config.getPassword();
        msChapV2Material = null;
        pendingPeerChallenge = null;
        pendingAuthenticatorChallenge = null;
        pendingAuthenticatorResponse = null;
        lastChapIdentifier = -1;
        nextIdentifier = 1;
        lastClientLcpRequestIdentifier = -1;
        lastClientIpcpRequestIdentifier = -1;
        currentLcpOptions = INITIAL_LCP_OPTIONS.clone();
        currentIpcpOptions = IPCP_IP_ADDRESS_OPTION.clone();
        assignedIpv4Address = new byte[] {0, 0, 0, 0};
        peerIpv4Address = new byte[] {0, 0, 0, 0};
        higherLayerAuthKey = zeroedHlak();
        // Future implementation: LCP, wire-format MS-CHAPv2 exchange and IPv4 IPCP polishing.
    }

    public void stop() {
        negotiated = false;
        lcpPeerRequestAcknowledged = false;
        lcpClientRequestAcknowledged = false;
        chapResponseSent = false;
        chapAuthenticated = false;
        ipcpPeerRequestAcknowledged = false;
        ipcpClientRequestAcknowledged = false;
        username = null;
        password = null;
        msChapV2Material = null;
        pendingPeerChallenge = null;
        pendingAuthenticatorChallenge = null;
        pendingAuthenticatorResponse = null;
        lastChapIdentifier = -1;
        nextIdentifier = 1;
        lastClientLcpRequestIdentifier = -1;
        lastClientIpcpRequestIdentifier = -1;
        currentLcpOptions = INITIAL_LCP_OPTIONS.clone();
        currentIpcpOptions = IPCP_IP_ADDRESS_OPTION.clone();
        assignedIpv4Address = new byte[] {0, 0, 0, 0};
        peerIpv4Address = new byte[] {0, 0, 0, 0};
        higherLayerAuthKey = zeroedHlak();
    }

    public boolean isNegotiated() {
        return negotiated;
    }

    public boolean hasDerivedHigherLayerAuthKey() {
        return msChapV2Material != null;
    }

    public boolean isChapAuthenticated() {
        return chapAuthenticated;
    }

    public boolean isIpcpAcknowledged() {
        return ipcpPeerRequestAcknowledged && ipcpClientRequestAcknowledged;
    }

    public boolean isReadyForTunnel() {
        return negotiated
            && lcpPeerRequestAcknowledged
            && lcpClientRequestAcknowledged
            && chapAuthenticated
            && isIpcpAcknowledged()
            && hasAssignedIpv4Address()
            && hasDerivedHigherLayerAuthKey();
    }

    public byte[] getHigherLayerAuthKey() {
        return higherLayerAuthKey.clone();
    }

    public byte[] getAssignedIpv4Address() {
        return assignedIpv4Address.clone();
    }

    public byte[] getPeerIpv4Address() {
        return peerIpv4Address.clone();
    }

    public byte[] handleIncoming(byte[] frame) throws IOException {
        ensureNegotiated();
        PppPacketUtil.PppPacket packet = PppPacketUtil.parse(frame);
        switch (packet.getProtocol()) {
            case PppConstants.PROTOCOL_LCP:
                return handleLcp(packet);
            case PppConstants.PROTOCOL_CHAP:
                return handleChap(frame, packet);
            case PppConstants.PROTOCOL_IPCP:
                return handleIpcp(packet);
            default:
                if (PppConstants.isNetworkControlProtocol(packet.getProtocol())) {
                    return handleGenericNcp(packet);
                }
                throw new IOException("Unsupported PPP protocol 0x" + Integer.toHexString(packet.getProtocol()));
        }
    }

    public byte[] createInitialLcpConfigureRequest() throws IOException {
        ensureNegotiated();
        int identifier = nextIdentifier();
        lastClientLcpRequestIdentifier = identifier;
        return PppPacketUtil.buildLcpConfigureRequest(identifier, currentLcpOptions);
    }

    public byte[] createIpcpConfigureRequest() throws IOException {
        ensureNegotiated();
        int identifier = nextIdentifier();
        lastClientIpcpRequestIdentifier = identifier;
        ipcpClientRequestAcknowledged = false;
        return PppPacketUtil.buildIpcpConfigureRequest(identifier, currentIpcpOptions);
    }

    public byte[] createLcpProtocolReject(int rejectedProtocol, byte[] rejectedPayload) throws IOException {
        ensureNegotiated();
        return PppPacketUtil.buildLcpProtocolReject(nextIdentifier(), rejectedProtocol, rejectedPayload);
    }

    public MsChapV2Material completeMsChapV2(byte[] authenticatorChallenge, byte[] peerChallenge)
        throws IOException {
        ensureNegotiated();

        msChapV2Material = MsChapV2Util.deriveMaterial(
            username,
            password,
            authenticatorChallenge,
            peerChallenge
        );
        higherLayerAuthKey = msChapV2Material.getHigherLayerAuthKey();
        return msChapV2Material;
    }

    public MsChapV2Material getMsChapV2Material() {
        return msChapV2Material;
    }

    private byte[] handleLcp(PppPacketUtil.PppPacket packet) throws IOException {
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_REQUEST) {
            if (isPeerAuthOptionAcceptable(packet.getPayload())) {
                lcpPeerRequestAcknowledged = true;
                return PppPacketUtil.buildConfigureAck(
                    PppConstants.PROTOCOL_LCP,
                    packet.getIdentifier(),
                    packet.getPayload()
                );
            }
            lcpPeerRequestAcknowledged = false;
            return PppPacketUtil.buildConfigureNak(
                PppConstants.PROTOCOL_LCP,
                packet.getIdentifier(),
                REQUIRED_LCP_AUTH_OPTION
            );
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_ACK) {
            if (lastClientLcpRequestIdentifier == -1 || packet.getIdentifier() != lastClientLcpRequestIdentifier) {
                throw new IOException("Unexpected LCP Configure-Ack identifier " + packet.getIdentifier());
            }
            lcpClientRequestAcknowledged = true;
            return null;
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_NAK) {
            currentLcpOptions = normalizeConfigureSuggestion(packet.getPayload(), currentLcpOptions);
            int identifier = nextIdentifier();
            lastClientLcpRequestIdentifier = identifier;
            lcpClientRequestAcknowledged = false;
            return PppPacketUtil.buildConfigureRequest(
                PppConstants.PROTOCOL_LCP,
                identifier,
                currentLcpOptions
            );
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_REJECT) {
            currentLcpOptions = removeRejectedOptions(currentLcpOptions, packet.getPayload());
            int identifier = nextIdentifier();
            lastClientLcpRequestIdentifier = identifier;
            lcpClientRequestAcknowledged = false;
            return PppPacketUtil.buildConfigureRequest(
                PppConstants.PROTOCOL_LCP,
                identifier,
                currentLcpOptions
            );
        }
        if (packet.getCode() == PppConstants.CODE_TERMINATE_REQUEST) {
            return PppPacketUtil.buildTerminateAck(
                PppConstants.PROTOCOL_LCP,
                packet.getIdentifier(),
                packet.getPayload()
            );
        }
        if (packet.getCode() == PppConstants.CODE_ECHO_REQUEST) {
            return PppPacketUtil.buildLcpEchoReply(packet.getIdentifier(), packet.getPayload());
        }
        throw new IOException("Unsupported LCP code " + packet.getCode());
    }

    private byte[] handleChap(byte[] frame, PppPacketUtil.PppPacket packet) throws IOException {
        if (packet.getCode() == PppConstants.CHAP_CODE_CHALLENGE) {
            PppPacketUtil.ChapChallenge challenge = PppPacketUtil.parseChapChallenge(frame);
            pendingAuthenticatorChallenge = challenge.getChallenge();
            pendingPeerChallenge = new byte[16];
            SECURE_RANDOM.nextBytes(pendingPeerChallenge);

            MsChapV2Material material = MsChapV2Util.deriveMaterial(
                username,
                password,
                pendingAuthenticatorChallenge,
                pendingPeerChallenge
            );
            pendingAuthenticatorResponse = material.getAuthenticatorResponse();
            lastChapIdentifier = challenge.getIdentifier();
            chapResponseSent = true;

            return PppPacketUtil.buildChapMsChapV2Response(
                challenge.getIdentifier(),
                pendingPeerChallenge,
                material.getNtResponse(),
                username
            );
        }
        if (packet.getCode() == PppConstants.CHAP_CODE_SUCCESS) {
            if (!chapResponseSent || pendingAuthenticatorChallenge == null || pendingPeerChallenge == null) {
                throw new IOException("Received CHAP success before CHAP response");
            }
            PppPacketUtil.ChapSuccess success = PppPacketUtil.parseChapSuccess(frame);
            if (lastChapIdentifier != -1 && success.getIdentifier() != lastChapIdentifier) {
                throw new IOException("Unexpected CHAP success identifier " + success.getIdentifier());
            }
            String successMessage = success.getMessage();
            if (successMessage != null && !successMessage.isEmpty()) {
                String upper = successMessage.toUpperCase(Locale.US);
                if (upper.contains("S=") && !upper.contains(pendingAuthenticatorResponse.toUpperCase(Locale.US))) {
                    throw new IOException("CHAP success authenticator response mismatch");
                }
            }
            msChapV2Material = MsChapV2Util.deriveMaterial(
                username,
                password,
                pendingAuthenticatorChallenge,
                pendingPeerChallenge
            );
            higherLayerAuthKey = msChapV2Material.getHigherLayerAuthKey();
            chapAuthenticated = true;
            return null;
        }
        if (packet.getCode() == PppConstants.CHAP_CODE_FAILURE) {
            throw new IOException("PPP CHAP authentication failed");
        }
        throw new IOException("Unsupported CHAP code " + packet.getCode());
    }

    private byte[] handleIpcp(PppPacketUtil.PppPacket packet) throws IOException {
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_REQUEST) {
            capturePeerIpv4(packet.getPayload());
            ipcpPeerRequestAcknowledged = true;
            return PppPacketUtil.buildConfigureAck(
                PppConstants.PROTOCOL_IPCP,
                packet.getIdentifier(),
                packet.getPayload()
            );
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_ACK) {
            if (lastClientIpcpRequestIdentifier == -1 || packet.getIdentifier() != lastClientIpcpRequestIdentifier) {
                throw new IOException("Unexpected IPCP Configure-Ack identifier " + packet.getIdentifier());
            }
            captureAssignedIpv4(packet.getPayload());
            if (!hasAssignedIpv4Address()) {
                throw new IOException("IPCP Configure-Ack does not include usable local IPv4");
            }
            ipcpClientRequestAcknowledged = true;
            return null;
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_NAK) {
            currentIpcpOptions = normalizeIpcpSuggestion(packet.getPayload(), currentIpcpOptions);
            int identifier = nextIdentifier();
            lastClientIpcpRequestIdentifier = identifier;
            ipcpClientRequestAcknowledged = false;
            return PppPacketUtil.buildConfigureRequest(
                PppConstants.PROTOCOL_IPCP,
                identifier,
                currentIpcpOptions
            );
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_REJECT) {
            currentIpcpOptions = removeRejectedOptions(currentIpcpOptions, packet.getPayload());
            if (currentIpcpOptions.length == 0) {
                throw new IOException("Peer rejected all IPCP options");
            }
            int identifier = nextIdentifier();
            lastClientIpcpRequestIdentifier = identifier;
            ipcpClientRequestAcknowledged = false;
            return PppPacketUtil.buildConfigureRequest(
                PppConstants.PROTOCOL_IPCP,
                identifier,
                currentIpcpOptions
            );
        }
        if (packet.getCode() == PppConstants.CODE_TERMINATE_REQUEST) {
            return PppPacketUtil.buildTerminateAck(
                PppConstants.PROTOCOL_IPCP,
                packet.getIdentifier(),
                packet.getPayload()
            );
        }
        throw new IOException("Unsupported IPCP code " + packet.getCode());
    }

    /**
     * Handles IPv6CP, CCP, and other NCPs after tunnel is up. These must not get LCP Protocol-Reject.
     */
    private byte[] handleGenericNcp(PppPacketUtil.PppPacket packet) throws IOException {
        int protocol = packet.getProtocol();
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_REQUEST) {
            if (protocol == PppConstants.PROTOCOL_CCP) {
                return PppPacketUtil.buildConfigureReject(
                    protocol,
                    packet.getIdentifier(),
                    packet.getPayload()
                );
            }
            return PppPacketUtil.buildConfigureAck(
                protocol,
                packet.getIdentifier(),
                packet.getPayload()
            );
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_ACK) {
            return null;
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_NAK) {
            return PppPacketUtil.buildConfigureRequest(
                protocol,
                nextIdentifier(),
                packet.getPayload()
            );
        }
        if (packet.getCode() == PppConstants.CODE_CONFIGURE_REJECT) {
            return PppPacketUtil.buildConfigureRequest(
                protocol,
                nextIdentifier(),
                new byte[0]
            );
        }
        if (packet.getCode() == PppConstants.CODE_TERMINATE_REQUEST) {
            return PppPacketUtil.buildTerminateAck(
                protocol,
                packet.getIdentifier(),
                packet.getPayload()
            );
        }
        throw new IOException("Unsupported NCP code " + packet.getCode());
    }

    private boolean isPeerAuthOptionAcceptable(byte[] options) throws IOException {
        if (options == null || options.length == 0) {
            return false;
        }
        PppPacketUtil.PppOption auth = PppPacketUtil.findOption(options, PppConstants.OPTION_AUTHENTICATION_PROTOCOL);
        if (auth == null || auth.getValue().length != 3) {
            return false;
        }
        byte[] value = auth.getValue();
        int protocol = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
        int algorithm = value[2] & 0xFF;
        return protocol == PppConstants.PROTOCOL_CHAP && algorithm == PppConstants.CHAP_ALGORITHM_MSCHAPV2;
    }

    private void ensureNegotiated() throws IOException {
        if (!negotiated) {
            throw new IOException("PPP session is not ready for MS-CHAPv2");
        }
    }

    private int nextIdentifier() {
        int current = nextIdentifier & 0xFF;
        nextIdentifier = (nextIdentifier + 1) & 0xFF;
        if (nextIdentifier == 0) {
            nextIdentifier = 1;
        }
        return current;
    }

    private byte[] normalizeConfigureSuggestion(byte[] suggested, byte[] fallback) {
        if (suggested == null || suggested.length == 0) {
            return fallback.clone();
        }
        try {
            // Client-side LCP options are negotiable; only require auth option in peer request.
            PppPacketUtil.parseOptions(suggested);
            return suggested.clone();
        } catch (IOException ignored) {
            // Keep fallback if the suggestion is malformed.
        }
        return fallback.clone();
    }

    private byte[] normalizeIpcpSuggestion(byte[] suggested, byte[] fallback) throws IOException {
        if (suggested == null || suggested.length == 0) {
            return fallback.clone();
        }

        PppPacketUtil.PppOption ipAddressOption = PppPacketUtil.findOption(
            suggested,
            PppConstants.OPTION_IP_ADDRESS
        );
        if (ipAddressOption == null || ipAddressOption.getValue().length != 4) {
            return suggested.clone();
        }

        byte[] option = new byte[6];
        option[0] = (byte) PppConstants.OPTION_IP_ADDRESS;
        option[1] = 0x06;
        byte[] value = ipAddressOption.getValue();
        if (isZeroIpv4(value)) {
            return fallback.clone();
        }
        System.arraycopy(value, 0, option, 2, value.length);
        assignedIpv4Address = value.clone();
        return option;
    }

    private void captureAssignedIpv4(byte[] options) throws IOException {
        PppPacketUtil.PppOption ipAddressOption = PppPacketUtil.findOption(
            options,
            PppConstants.OPTION_IP_ADDRESS
        );
        if (ipAddressOption != null && ipAddressOption.getValue().length == 4 && !isZeroIpv4(ipAddressOption.getValue())) {
            assignedIpv4Address = ipAddressOption.getValue().clone();
        }
    }

    private void capturePeerIpv4(byte[] options) throws IOException {
        PppPacketUtil.PppOption ipAddressOption = PppPacketUtil.findOption(
            options,
            PppConstants.OPTION_IP_ADDRESS
        );
        if (ipAddressOption != null && ipAddressOption.getValue().length == 4 && !isZeroIpv4(ipAddressOption.getValue())) {
            peerIpv4Address = ipAddressOption.getValue().clone();
        }
    }

    private boolean isZeroIpv4(byte[] address) {
        return address != null
            && address.length == 4
            && address[0] == 0
            && address[1] == 0
            && address[2] == 0
            && address[3] == 0;
    }

    private boolean hasAssignedIpv4Address() {
        return !isZeroIpv4(assignedIpv4Address);
    }

    private byte[] removeRejectedOptions(byte[] currentOptions, byte[] rejectedOptions) throws IOException {
        if (currentOptions == null || currentOptions.length == 0) {
            return new byte[0];
        }
        if (rejectedOptions == null || rejectedOptions.length == 0) {
            return currentOptions.clone();
        }

        java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
        int offset = 0;
        while (offset < currentOptions.length) {
            if (offset + 2 > currentOptions.length) {
                throw new IOException("PPP option header is truncated");
            }
            int length = currentOptions[offset + 1] & 0xFF;
            if (length < 2 || offset + length > currentOptions.length) {
                throw new IOException("PPP option length is invalid");
            }
            if (!containsOption(rejectedOptions, currentOptions, offset, length)) {
                kept.write(currentOptions, offset, length);
            }
            offset += length;
        }
        return kept.toByteArray();
    }

    private boolean containsOption(byte[] optionsBlock, byte[] candidateBlock, int candidateOffset, int candidateLength)
        throws IOException {
        int offset = 0;
        while (offset < optionsBlock.length) {
            if (offset + 2 > optionsBlock.length) {
                throw new IOException("PPP option block is truncated");
            }
            int length = optionsBlock[offset + 1] & 0xFF;
            if (length < 2 || offset + length > optionsBlock.length) {
                throw new IOException("PPP option block length is invalid");
            }
            if (length == candidateLength) {
                boolean match = true;
                for (int i = 0; i < length; i++) {
                    if (optionsBlock[offset + i] != candidateBlock[candidateOffset + i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
            offset += length;
        }
        return false;
    }

    public static byte[] zeroedHlak() {
        return new byte[32];
    }
}
