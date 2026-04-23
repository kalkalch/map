package com.map.sstp.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class SstpPacketUtil {
    private SstpPacketUtil() {}

    public static byte[] buildCallConnectRequest() throws IOException {
        byte[] attributeValue = ByteBuffer.allocate(2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort((short) SstpConstants.PROTOCOL_PPP)
            .array();

        byte[] attribute = buildAttribute(
            SstpConstants.ATTR_ENCAPSULATED_PROTOCOL_ID,
            attributeValue
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SstpConstants.VERSION_1_0);
        out.write(SstpConstants.CONTROL_PACKET);
        writeLength(out, SstpConstants.CONTROL_HEADER_SIZE + attribute.length);
        writeUnsignedShort(out, SstpConstants.MSG_CALL_CONNECT_REQUEST);
        writeUnsignedShort(out, 1);
        out.write(attribute);
        return out.toByteArray();
    }

    public static byte[] buildDataPacket(byte[] payload) throws IOException {
        byte[] safePayload = payload != null ? payload : new byte[0];
        int totalLength = SstpConstants.HEADER_SIZE + safePayload.length;
        if (totalLength > SstpConstants.MAX_PACKET_LENGTH) {
            throw new IOException(
                "SSTP data packet is too large: " + totalLength + " (max " + SstpConstants.MAX_PACKET_LENGTH + ")"
            );
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SstpConstants.VERSION_1_0);
        out.write(0x00);
        writeLength(out, totalLength);
        out.write(safePayload);
        return out.toByteArray();
    }

    public static byte[] buildControlPacket(int messageType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SstpConstants.VERSION_1_0);
        out.write(SstpConstants.CONTROL_PACKET);
        writeLength(out, SstpConstants.CONTROL_HEADER_SIZE);
        writeUnsignedShort(out, messageType);
        writeUnsignedShort(out, 0);
        return out.toByteArray();
    }

    public static ControlHeader parseControlHeader(byte[] packet) throws IOException {
        if (packet == null || packet.length < SstpConstants.CONTROL_HEADER_SIZE) {
            throw new IOException("SSTP packet is too short");
        }

        int version = packet[0] & 0xFF;
        int flags = packet[1] & 0xFF;
        boolean control = (flags & SstpConstants.CONTROL_PACKET) == SstpConstants.CONTROL_PACKET;
        int length = decodeLength(packet[2], packet[3]);
        int messageType = unsignedShort(packet[4], packet[5]);
        int attributeCount = unsignedShort(packet[6], packet[7]);

        if (!control) {
            throw new IOException("Received SSTP data packet during control phase");
        }
        if (length > packet.length) {
            throw new IOException("Incomplete SSTP control packet");
        }

        return new ControlHeader(version, length, messageType, attributeCount);
    }

    public static CryptoBindingRequest parseCryptoBindingRequest(byte[] packet) throws IOException {
        ControlHeader header = parseControlHeader(packet);
        if (header.getMessageType() != SstpConstants.MSG_CALL_CONNECT_ACK) {
            throw new IOException("Expected Call Connect Ack packet");
        }
        if (packet.length < SstpConstants.CONTROL_HEADER_SIZE + SstpConstants.ATTRIBUTE_HEADER_SIZE) {
            throw new IOException("Missing SSTP attribute");
        }

        int offset = SstpConstants.CONTROL_HEADER_SIZE;
        int reserved = packet[offset] & 0xFF;
        int attributeId = packet[offset + 1] & 0xFF;
        int attributeLength = decodeLength(packet[offset + 2], packet[offset + 3]);
        if (attributeId != SstpConstants.ATTR_CRYPTO_BINDING_REQUEST) {
            throw new IOException("Unexpected SSTP attribute id " + attributeId);
        }
        if (attributeLength != SstpConstants.ACK_ATTRIBUTE_TOTAL_LENGTH) {
            throw new IOException("Unexpected crypto binding request length " + attributeLength);
        }

        int valueOffset = offset + SstpConstants.ATTRIBUTE_HEADER_SIZE;
        int hashProtocolBitmask = packet[valueOffset + 3] & 0xFF;
        byte[] nonce = Arrays.copyOfRange(
            packet,
            valueOffset + 4,
            valueOffset + 4 + SstpConstants.ACK_NONCE_LENGTH
        );
        return new CryptoBindingRequest(header, reserved, hashProtocolBitmask, nonce);
    }

    public static DataPacket parseDataPacket(byte[] packet) throws IOException {
        if (packet == null || packet.length < SstpConstants.HEADER_SIZE) {
            throw new IOException("SSTP data packet is too short");
        }

        int version = packet[0] & 0xFF;
        int flags = packet[1] & 0xFF;
        if ((flags & SstpConstants.CONTROL_PACKET) == SstpConstants.CONTROL_PACKET) {
            throw new IOException("Received SSTP control packet during data phase");
        }

        int length = decodeLength(packet[2], packet[3]);
        if (length < SstpConstants.HEADER_SIZE) {
            throw new IOException("Invalid SSTP data packet length");
        }
        if (length != packet.length) {
            throw new IOException("Incomplete SSTP data packet");
        }

        byte[] payload = Arrays.copyOfRange(packet, SstpConstants.HEADER_SIZE, packet.length);
        return new DataPacket(version, length, payload);
    }

    public static byte[] buildCallConnected(CryptoBindingMaterial material) throws IOException {
        if (material == null) {
            throw new IOException("Missing crypto binding material");
        }
        ByteArrayOutputStream attributeValue = new ByteArrayOutputStream();
        attributeValue.write(0x00);
        attributeValue.write(0x00);
        attributeValue.write(0x00);
        attributeValue.write(material.getHashProtocol() & 0xFF);
        attributeValue.write(material.getNonce());
        attributeValue.write(material.getCertificateHash());
        attributeValue.write(material.getCompoundMac());

        byte[] attribute = buildAttribute(
            SstpConstants.ATTR_CRYPTO_BINDING,
            attributeValue.toByteArray()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SstpConstants.VERSION_1_0);
        out.write(SstpConstants.CONTROL_PACKET);
        writeLength(out, SstpConstants.CALL_CONNECTED_TOTAL_LENGTH);
        writeUnsignedShort(out, SstpConstants.MSG_CALL_CONNECTED);
        writeUnsignedShort(out, 1);
        out.write(attribute);
        return out.toByteArray();
    }

    public static byte[] buildCallConnectedForMac(
        int hashProtocol,
        byte[] nonce,
        byte[] certificateHash
    ) throws IOException {
        ByteArrayOutputStream attributeValue = new ByteArrayOutputStream();
        attributeValue.write(0x00);
        attributeValue.write(0x00);
        attributeValue.write(0x00);
        attributeValue.write(hashProtocol & 0xFF);
        attributeValue.write(nonce);
        attributeValue.write(certificateHash);
        attributeValue.write(new byte[32]);

        byte[] attribute = buildAttribute(
            SstpConstants.ATTR_CRYPTO_BINDING,
            attributeValue.toByteArray()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SstpConstants.VERSION_1_0);
        out.write(SstpConstants.CONTROL_PACKET);
        writeLength(out, SstpConstants.CALL_CONNECTED_TOTAL_LENGTH);
        writeUnsignedShort(out, SstpConstants.MSG_CALL_CONNECTED);
        writeUnsignedShort(out, 1);
        out.write(attribute);
        return out.toByteArray();
    }

    private static byte[] buildAttribute(int attributeId, byte[] value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00);
        out.write(attributeId & 0xFF);
        writeLength(out, SstpConstants.ATTRIBUTE_HEADER_SIZE + value.length);
        out.write(value);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) throws IOException {
        if (length < 0 || length > SstpConstants.MAX_PACKET_LENGTH) {
            throw new IOException("SSTP packet length out of range: " + length);
        }
        int packed = length & 0x0FFF;
        out.write((packed >> 8) & 0x0F);
        out.write(packed & 0xFF);
    }

    private static void writeUnsignedShort(ByteArrayOutputStream out, int value) throws IOException {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int decodeLength(byte high, byte low) {
        return ((high & 0x0F) << 8) | (low & 0xFF);
    }

    private static int unsignedShort(byte high, byte low) {
        return ((high & 0xFF) << 8) | (low & 0xFF);
    }

    public static final class ControlHeader {
        private final int version;
        private final int length;
        private final int messageType;
        private final int attributeCount;

        public ControlHeader(int version, int length, int messageType, int attributeCount) {
            this.version = version;
            this.length = length;
            this.messageType = messageType;
            this.attributeCount = attributeCount;
        }

        public int getVersion() {
            return version;
        }

        public int getLength() {
            return length;
        }

        public int getMessageType() {
            return messageType;
        }

        public int getAttributeCount() {
            return attributeCount;
        }
    }

    public static final class CryptoBindingRequest {
        private final ControlHeader header;
        private final int reserved;
        private final int hashProtocolBitmask;
        private final byte[] nonce;

        public CryptoBindingRequest(
            ControlHeader header,
            int reserved,
            int hashProtocolBitmask,
            byte[] nonce
        ) {
            this.header = header;
            this.reserved = reserved;
            this.hashProtocolBitmask = hashProtocolBitmask;
            this.nonce = nonce;
        }

        public ControlHeader getHeader() {
            return header;
        }

        public int getReserved() {
            return reserved;
        }

        public int getHashProtocolBitmask() {
            return hashProtocolBitmask;
        }

        public byte[] getNonce() {
            return nonce;
        }
    }

    public static final class CryptoBindingMaterial {
        private final int hashProtocol;
        private final byte[] nonce;
        private final byte[] certificateHash;
        private final byte[] compoundMac;

        public CryptoBindingMaterial(
            int hashProtocol,
            byte[] nonce,
            byte[] certificateHash,
            byte[] compoundMac
        ) {
            this.hashProtocol = hashProtocol;
            this.nonce = nonce;
            this.certificateHash = certificateHash;
            this.compoundMac = compoundMac;
        }

        public int getHashProtocol() {
            return hashProtocol;
        }

        public byte[] getNonce() {
            return nonce;
        }

        public byte[] getCertificateHash() {
            return certificateHash;
        }

        public byte[] getCompoundMac() {
            return compoundMac;
        }
    }

    public static final class DataPacket {
        private final int version;
        private final int length;
        private final byte[] payload;

        public DataPacket(int version, int length, byte[] payload) {
            this.version = version;
            this.length = length;
            this.payload = payload.clone();
        }

        public int getVersion() {
            return version;
        }

        public int getLength() {
            return length;
        }

        public byte[] getPayload() {
            return payload.clone();
        }
    }
}
