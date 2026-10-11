package com.map.sstp.protocol;

public final class SstpConstants {
    private SstpConstants() {}

    public static final byte VERSION_1_0 = 0x10;
    public static final byte CONTROL_PACKET = 0x01;

    public static final int HEADER_SIZE = 4;
    public static final int CONTROL_HEADER_SIZE = 8;
    public static final int ATTRIBUTE_HEADER_SIZE = 4;
    public static final int MAX_PACKET_LENGTH = 0x0FFF;
    public static final int MAX_DATA_PAYLOAD_LENGTH = MAX_PACKET_LENGTH - HEADER_SIZE;

    public static final int MSG_CALL_CONNECT_REQUEST = 0x0001;
    public static final int MSG_CALL_CONNECT_ACK = 0x0002;
    public static final int MSG_CALL_CONNECT_NAK = 0x0003;
    public static final int MSG_CALL_CONNECTED = 0x0004;
    public static final int MSG_CALL_ABORT = 0x0005;
    public static final int MSG_CALL_DISCONNECT = 0x0006;
    public static final int MSG_CALL_DISCONNECT_ACK = 0x0007;
    public static final int MSG_ECHO_REQUEST = 0x0008;
    public static final int MSG_ECHO_RESPONSE = 0x0009;

    public static final int ATTR_ENCAPSULATED_PROTOCOL_ID = 0x01;
    public static final int ATTR_STATUS_INFO = 0x02;
    public static final int ATTR_CRYPTO_BINDING = 0x03;
    public static final int ATTR_CRYPTO_BINDING_REQUEST = 0x04;

    public static final int PROTOCOL_PPP = 0x0001;
    public static final int CERT_HASH_PROTOCOL_SHA1 = 0x01;
    public static final int CERT_HASH_PROTOCOL_SHA256 = 0x02;

    public static final int ACK_ATTRIBUTE_TOTAL_LENGTH = 40;
    public static final int ACK_NONCE_LENGTH = 32;
    public static final int CALL_CONNECTED_TOTAL_LENGTH = 112;
    public static final int CRYPTO_BINDING_ATTRIBUTE_LENGTH = 104;
}
