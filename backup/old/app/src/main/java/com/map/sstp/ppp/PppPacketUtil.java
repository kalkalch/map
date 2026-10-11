package com.map.sstp.ppp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PPP framing helpers for LCP/CHAP/IPCP packets over SSTP data packets.
 */
public final class PppPacketUtil {
    private static final int PPP_ADDRESS_CONTROL_SIZE = 2;
    private static final int PPP_MIN_PROTOCOL_SIZE = 1;
    private static final int PPP_CONTROL_HEADER_SIZE = 4;
    private static final int CHAP_MSCHAPV2_RESPONSE_VALUE_SIZE = 49;
    private static final int IPV4_VERSION = 4;
    private static final int IPV6_VERSION = 6;

    private PppPacketUtil() {}

    public static byte[] buildLcpConfigureRequest(int identifier, byte[] options) throws IOException {
        return buildConfigureRequest(PppConstants.PROTOCOL_LCP, identifier, options);
    }

    public static byte[] buildIpcpConfigureRequest(int identifier, byte[] options) throws IOException {
        return buildConfigureRequest(PppConstants.PROTOCOL_IPCP, identifier, options);
    }

    public static byte[] buildConfigureRequest(int protocol, int identifier, byte[] options) throws IOException {
        return buildControlPacket(
            protocol,
            PppConstants.CODE_CONFIGURE_REQUEST,
            identifier,
            options
        );
    }

    public static byte[] buildConfigureAck(int protocol, int identifier, byte[] options) throws IOException {
        return buildControlPacket(
            protocol,
            PppConstants.CODE_CONFIGURE_ACK,
            identifier,
            options
        );
    }

    public static byte[] buildConfigureNak(int protocol, int identifier, byte[] options) throws IOException {
        return buildControlPacket(
            protocol,
            PppConstants.CODE_CONFIGURE_NAK,
            identifier,
            options
        );
    }

    public static byte[] buildConfigureReject(int protocol, int identifier, byte[] options) throws IOException {
        return buildControlPacket(
            protocol,
            PppConstants.CODE_CONFIGURE_REJECT,
            identifier,
            options
        );
    }

    public static byte[] buildTerminateAck(int protocol, int identifier, byte[] payload) throws IOException {
        return buildControlPacket(
            protocol,
            PppConstants.CODE_TERMINATE_ACK,
            identifier,
            payload
        );
    }

    public static byte[] buildLcpEchoReply(int identifier, byte[] payload) throws IOException {
        return buildControlPacket(
            PppConstants.PROTOCOL_LCP,
            PppConstants.CODE_ECHO_REPLY,
            identifier,
            payload
        );
    }

    public static byte[] buildLcpProtocolReject(int identifier, int rejectedProtocol, byte[] rejectedPayload)
        throws IOException {
        byte[] safeRejectedPayload = rejectedPayload != null ? rejectedPayload : new byte[0];
        ByteArrayOutputStream payload = new ByteArrayOutputStream(safeRejectedPayload.length + 2);
        payload.write((rejectedProtocol >> 8) & 0xFF);
        payload.write(rejectedProtocol & 0xFF);
        payload.write(safeRejectedPayload);
        return buildControlPacket(
            PppConstants.PROTOCOL_LCP,
            PppConstants.CODE_PROTOCOL_REJECT,
            identifier,
            payload.toByteArray()
        );
    }

    public static byte[] buildChapMsChapV2Response(
        int identifier,
        byte[] peerChallenge,
        byte[] ntResponse,
        String username
    ) throws IOException {
        if (peerChallenge == null || peerChallenge.length != 16) {
            throw new IOException("MS-CHAPv2 peer challenge must be 16 bytes");
        }
        if (ntResponse == null || ntResponse.length != 24) {
            throw new IOException("MS-CHAPv2 NT-Response must be 24 bytes");
        }
        if (username == null || username.isEmpty()) {
            throw new IOException("MS-CHAPv2 username is required");
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(CHAP_MSCHAPV2_RESPONSE_VALUE_SIZE);
        payload.write(peerChallenge);
        payload.write(new byte[8]);
        payload.write(ntResponse);
        payload.write(0x00);
        payload.write(username.getBytes(StandardCharsets.US_ASCII));

        return buildControlPacket(
            PppConstants.PROTOCOL_CHAP,
            PppConstants.CHAP_CODE_RESPONSE,
            identifier,
            payload.toByteArray()
        );
    }

    public static PppPacket parse(byte[] frame) throws IOException {
        if (frame == null || frame.length < PPP_MIN_PROTOCOL_SIZE + PPP_CONTROL_HEADER_SIZE) {
            throw new IOException("PPP frame is too short");
        }

        int offset = 0;
        if (hasAddressControlPrefix(frame)) {
            if (frame.length < PPP_ADDRESS_CONTROL_SIZE + PPP_MIN_PROTOCOL_SIZE + PPP_CONTROL_HEADER_SIZE) {
                throw new IOException("PPP frame is too short");
            }
            offset = PPP_ADDRESS_CONTROL_SIZE;
        }

        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buffer.position(offset);

        int protocol;
        int protocolFieldLength;
        if ((frame[offset] & 0x01) == 0x01) {
            protocol = frame[offset] & 0xFF;
            protocolFieldLength = 1;
            buffer.get();
        } else {
            if (frame.length < offset + 2 + PPP_CONTROL_HEADER_SIZE) {
                throw new IOException("PPP frame is too short");
            }
            protocol = buffer.getShort() & 0xFFFF;
            protocolFieldLength = 2;
        }
        int code = buffer.get() & 0xFF;
        int identifier = buffer.get() & 0xFF;
        int length = buffer.getShort() & 0xFFFF;

        if (length < PPP_CONTROL_HEADER_SIZE) {
            throw new IOException("PPP payload length is invalid");
        }
        int payloadLength = length - PPP_CONTROL_HEADER_SIZE;
        if (frame.length != offset + protocolFieldLength + length) {
            throw new IOException("PPP frame length mismatch");
        }

        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        return new PppPacket(protocol, code, identifier, payload);
    }

    public static ChapChallenge parseChapChallenge(byte[] frame) throws IOException {
        PppPacket packet = parse(frame);
        if (packet.getProtocol() != PppConstants.PROTOCOL_CHAP) {
            throw new IOException("PPP packet is not CHAP");
        }
        if (packet.getCode() != PppConstants.CHAP_CODE_CHALLENGE) {
            throw new IOException("PPP CHAP packet is not a challenge");
        }
        if (packet.getPayload().length < 1) {
            throw new IOException("PPP CHAP challenge payload is too short");
        }

        int valueSize = packet.getPayload()[0] & 0xFF;
        if (packet.getPayload().length < 1 + valueSize) {
            throw new IOException("PPP CHAP challenge value is truncated");
        }
        byte[] challenge = new byte[valueSize];
        System.arraycopy(packet.getPayload(), 1, challenge, 0, challenge.length);
        int nameLength = packet.getPayload().length - 1 - valueSize;
        byte[] nameBytes = new byte[nameLength];
        System.arraycopy(packet.getPayload(), 1 + valueSize, nameBytes, 0, nameLength);

        return new ChapChallenge(
            packet.getIdentifier(),
            challenge,
            new String(nameBytes, StandardCharsets.US_ASCII)
        );
    }

    public static ChapSuccess parseChapSuccess(byte[] frame) throws IOException {
        PppPacket packet = parse(frame);
        if (packet.getProtocol() != PppConstants.PROTOCOL_CHAP) {
            throw new IOException("PPP packet is not CHAP");
        }
        if (packet.getCode() != PppConstants.CHAP_CODE_SUCCESS) {
            throw new IOException("PPP CHAP packet is not a success");
        }
        return new ChapSuccess(
            packet.getIdentifier(),
            new String(packet.getPayload(), StandardCharsets.US_ASCII)
        );
    }

    public static byte[] buildDataFrame(int protocol, byte[] payload) throws IOException {
        byte[] safePayload = payload != null ? payload : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(PPP_ADDRESS_CONTROL_SIZE + 2 + safePayload.length)
            .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) PppConstants.ADDRESS);
        buffer.put((byte) PppConstants.CONTROL);
        buffer.putShort((short) protocol);
        buffer.put(safePayload);
        return buffer.array();
    }

    public static byte[] buildDataFrameForIpPacket(byte[] packet) throws IOException {
        return buildDataFrame(detectNetworkProtocol(packet), packet);
    }

    public static DataFrame parseDataFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length < PPP_MIN_PROTOCOL_SIZE + 1) {
            throw new IOException("PPP data frame is too short");
        }

        int offset = 0;
        if (hasAddressControlPrefix(frame)) {
            if (frame.length < PPP_ADDRESS_CONTROL_SIZE + PPP_MIN_PROTOCOL_SIZE + 1) {
                throw new IOException("PPP data frame is too short");
            }
            offset = PPP_ADDRESS_CONTROL_SIZE;
        }

        int protocol;
        int protocolFieldLength;
        if ((frame[offset] & 0x01) == 0x01) {
            protocol = frame[offset] & 0xFF;
            protocolFieldLength = 1;
        } else {
            if (frame.length < offset + 2 + 1) {
                throw new IOException("PPP data frame is too short");
            }
            protocol = ((frame[offset] & 0xFF) << 8) | (frame[offset + 1] & 0xFF);
            protocolFieldLength = 2;
        }

        byte[] payload = Arrays.copyOfRange(frame, offset + protocolFieldLength, frame.length);
        return new DataFrame(protocol, payload);
    }

    public static ConfigurePacket parseConfigurePacket(byte[] frame, int expectedProtocol) throws IOException {
        PppPacket packet = parse(frame);
        if (packet.getProtocol() != expectedProtocol) {
            throw new IOException("PPP packet protocol mismatch");
        }
        if (packet.getCode() != PppConstants.CODE_CONFIGURE_REQUEST
            && packet.getCode() != PppConstants.CODE_CONFIGURE_ACK
            && packet.getCode() != PppConstants.CODE_CONFIGURE_NAK
            && packet.getCode() != PppConstants.CODE_CONFIGURE_REJECT) {
            throw new IOException("PPP packet is not a configure packet");
        }
        return new ConfigurePacket(
            packet.getProtocol(),
            packet.getCode(),
            packet.getIdentifier(),
            packet.getPayload()
        );
    }

    public static List<PppOption> parseOptions(byte[] options) throws IOException {
        List<PppOption> parsed = new ArrayList<>();
        if (options == null) {
            return parsed;
        }

        int offset = 0;
        while (offset < options.length) {
            if (offset + 2 > options.length) {
                throw new IOException("PPP option header is truncated");
            }
            int type = options[offset] & 0xFF;
            int length = options[offset + 1] & 0xFF;
            if (length < 2 || offset + length > options.length) {
                throw new IOException("PPP option length is invalid");
            }
            byte[] value = Arrays.copyOfRange(options, offset + 2, offset + length);
            parsed.add(new PppOption(type, value));
            offset += length;
        }
        return parsed;
    }

    public static PppOption findOption(byte[] options, int type) throws IOException {
        for (PppOption option : parseOptions(options)) {
            if (option.getType() == type) {
                return option;
            }
        }
        return null;
    }

    private static byte[] buildControlPacket(int protocol, int code, int identifier, byte[] payload)
        throws IOException {
        byte[] safePayload = payload != null ? payload : new byte[0];
        int packetLength = PPP_CONTROL_HEADER_SIZE + safePayload.length;
        ByteBuffer buffer = ByteBuffer.allocate(PPP_ADDRESS_CONTROL_SIZE + 2 + packetLength)
            .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) PppConstants.ADDRESS);
        buffer.put((byte) PppConstants.CONTROL);
        buffer.putShort((short) protocol);
        buffer.put((byte) code);
        buffer.put((byte) (identifier & 0xFF));
        buffer.putShort((short) packetLength);
        buffer.put(safePayload);
        return buffer.array();
    }

    private static boolean hasAddressControlPrefix(byte[] frame) {
        return frame.length >= 2
            && (frame[0] & 0xFF) == PppConstants.ADDRESS
            && (frame[1] & 0xFF) == PppConstants.CONTROL;
    }

    private static int detectNetworkProtocol(byte[] packet) throws IOException {
        if (packet == null || packet.length == 0) {
            throw new IOException("IP packet is empty");
        }

        int version = (packet[0] >> 4) & 0x0F;
        if (version == IPV4_VERSION) {
            return PppConstants.PROTOCOL_IPV4;
        }
        if (version == IPV6_VERSION) {
            return PppConstants.PROTOCOL_IPV6;
        }
        throw new IOException("Unsupported IP version for PPP relay: " + version);
    }

    public static final class PppPacket {
        private final int protocol;
        private final int code;
        private final int identifier;
        private final byte[] payload;

        public PppPacket(int protocol, int code, int identifier, byte[] payload) {
            this.protocol = protocol;
            this.code = code;
            this.identifier = identifier;
            this.payload = payload.clone();
        }

        public int getProtocol() {
            return protocol;
        }

        public int getCode() {
            return code;
        }

        public int getIdentifier() {
            return identifier;
        }

        public byte[] getPayload() {
            return payload.clone();
        }
    }

    public static final class ChapChallenge {
        private final int identifier;
        private final byte[] challenge;
        private final String name;

        public ChapChallenge(int identifier, byte[] challenge, String name) {
            this.identifier = identifier;
            this.challenge = challenge.clone();
            this.name = name;
        }

        public int getIdentifier() {
            return identifier;
        }

        public byte[] getChallenge() {
            return challenge.clone();
        }

        public String getName() {
            return name;
        }
    }

    public static final class ChapSuccess {
        private final int identifier;
        private final String message;

        public ChapSuccess(int identifier, String message) {
            this.identifier = identifier;
            this.message = message;
        }

        public int getIdentifier() {
            return identifier;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class ConfigurePacket {
        private final int protocol;
        private final int code;
        private final int identifier;
        private final byte[] options;

        public ConfigurePacket(int protocol, int code, int identifier, byte[] options) {
            this.protocol = protocol;
            this.code = code;
            this.identifier = identifier;
            this.options = Arrays.copyOf(options, options.length);
        }

        public int getProtocol() {
            return protocol;
        }

        public int getCode() {
            return code;
        }

        public int getIdentifier() {
            return identifier;
        }

        public byte[] getOptions() {
            return Arrays.copyOf(options, options.length);
        }
    }

    public static final class PppOption {
        private final int type;
        private final byte[] value;

        public PppOption(int type, byte[] value) {
            this.type = type;
            this.value = Arrays.copyOf(value, value.length);
        }

        public int getType() {
            return type;
        }

        public byte[] getValue() {
            return Arrays.copyOf(value, value.length);
        }
    }

    public static final class DataFrame {
        private final int protocol;
        private final byte[] payload;

        public DataFrame(int protocol, byte[] payload) {
            this.protocol = protocol;
            this.payload = Arrays.copyOf(payload, payload.length);
        }

        public int getProtocol() {
            return protocol;
        }

        public byte[] getPayload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }
}
