package com.map.sstp.ppp;

public final class PppConstants {
    public static final int ADDRESS = 0xFF;
    public static final int CONTROL = 0x03;

    public static final int PROTOCOL_LCP = 0xC021;
    public static final int PROTOCOL_CHAP = 0xC223;
    public static final int PROTOCOL_IPCP = 0x8021;
    /** IPv6 Control Protocol (RFC 5072) — not the same as IPv6 datagram (0x0057). */
    public static final int PROTOCOL_IPV6CP = 0x8057;
    /** Compression Control Protocol (RFC 1962). */
    public static final int PROTOCOL_CCP = 0x80FD;
    /** Observed on some SSTP peers as an additional NCP after connect. */
    public static final int PROTOCOL_NCP_EXT_8281 = 0x8281;
    public static final int PROTOCOL_IPV4 = 0x0021;
    public static final int PROTOCOL_IPV6 = 0x0057;
    public static final int OPTION_AUTHENTICATION_PROTOCOL = 0x03;
    public static final int OPTION_IP_ADDRESS = 0x03;

    public static final int CODE_CONFIGURE_REQUEST = 0x01;
    public static final int CODE_CONFIGURE_ACK = 0x02;
    public static final int CODE_CONFIGURE_NAK = 0x03;
    public static final int CODE_CONFIGURE_REJECT = 0x04;
    public static final int CODE_TERMINATE_REQUEST = 0x05;
    public static final int CODE_TERMINATE_ACK = 0x06;
    public static final int CODE_PROTOCOL_REJECT = 0x08;
    public static final int CODE_ECHO_REQUEST = 0x09;
    public static final int CODE_ECHO_REPLY = 0x0A;

    public static final int CHAP_CODE_CHALLENGE = 0x01;
    public static final int CHAP_CODE_RESPONSE = 0x02;
    public static final int CHAP_CODE_SUCCESS = 0x03;
    public static final int CHAP_CODE_FAILURE = 0x04;
    public static final int CHAP_ALGORITHM_MSCHAPV2 = 0x81;

    private PppConstants() {}

    /**
     * PPP Network Control Protocols (IANA 0x8000–0x80ff) plus known vendor NCPs outside that block.
     * Same LCP-style framing as IPCP; must not be answered with LCP Protocol-Reject.
     */
    public static boolean isNetworkControlProtocol(int protocol) {
        if (protocol >= 0x8000 && protocol <= 0x80ff) {
            return true;
        }
        return protocol == PROTOCOL_NCP_EXT_8281;
    }
}
