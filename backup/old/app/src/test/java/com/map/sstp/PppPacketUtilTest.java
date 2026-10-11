package com.map.sstp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.map.sstp.ppp.PppConstants;
import com.map.sstp.ppp.PppPacketUtil;
import org.junit.Test;

public class PppPacketUtilTest {

    @Test
    public void buildLcpConfigureRequest_ShouldPreserveProtocolAndPayload() throws Exception {
        byte[] packet = PppPacketUtil.buildLcpConfigureRequest(
            0x11,
            hex("050612345678")
        );

        assertArrayEquals(hex("FF03"), slice(packet, 0, 2));
        PppPacketUtil.PppPacket parsed = PppPacketUtil.parse(packet);
        assertEquals(PppConstants.PROTOCOL_LCP, parsed.getProtocol());
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, parsed.getCode());
        assertEquals(0x11, parsed.getIdentifier());
        assertArrayEquals(hex("050612345678"), parsed.getPayload());
    }

    @Test
    public void buildChapMsChapV2Response_ShouldEncodePeerChallengeAndNtResponse() throws Exception {
        byte[] packet = PppPacketUtil.buildChapMsChapV2Response(
            0x22,
            hex("21402324255E262A28295F2B3A337C7E"),
            hex("82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF"),
            "User"
        );

        PppPacketUtil.PppPacket parsed = PppPacketUtil.parse(packet);
        assertEquals(PppConstants.PROTOCOL_CHAP, parsed.getProtocol());
        assertEquals(PppConstants.CHAP_CODE_RESPONSE, parsed.getCode());
        assertEquals(0x22, parsed.getIdentifier());
        assertEquals(54, parsed.getPayload().length);
        assertEquals(49, parsed.getPayload()[0] & 0xFF);
        assertArrayEquals(
            hex("21402324255E262A28295F2B3A337C7E"),
            slice(parsed.getPayload(), 1, 16)
        );
        assertArrayEquals(
            hex("82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF"),
            slice(parsed.getPayload(), 25, 24)
        );
    }

    @Test
    public void parseChapChallenge_ShouldExposeChallengeAndName() throws Exception {
        byte[] frame = hex("C2230140001D1000112233445566778899AABBCCDDEEFF4D696B726F54696B");

        PppPacketUtil.ChapChallenge challenge = PppPacketUtil.parseChapChallenge(frame);
        assertEquals(0x40, challenge.getIdentifier());
        assertArrayEquals(hex("00112233445566778899AABBCCDDEEFF"), challenge.getChallenge());
        assertEquals("MikroTik", challenge.getName());
    }

    @Test
    public void parseChapChallenge_ShouldAcceptAddressControlPrefix() throws Exception {
        byte[] frame = hex("FF03C2230140001D1000112233445566778899AABBCCDDEEFF4D696B726F54696B");

        PppPacketUtil.ChapChallenge challenge = PppPacketUtil.parseChapChallenge(frame);

        assertEquals(0x40, challenge.getIdentifier());
        assertArrayEquals(hex("00112233445566778899AABBCCDDEEFF"), challenge.getChallenge());
        assertEquals("MikroTik", challenge.getName());
    }

    @Test
    public void buildConfigureAck_ShouldMirrorIdentifierAndOptions() throws Exception {
        byte[] packet = PppPacketUtil.buildConfigureAck(
            PppConstants.PROTOCOL_IPCP,
            0x31,
            hex("0306C0A80164")
        );

        PppPacketUtil.ConfigurePacket parsed = PppPacketUtil.parseConfigurePacket(
            packet,
            PppConstants.PROTOCOL_IPCP
        );
        assertEquals(PppConstants.CODE_CONFIGURE_ACK, parsed.getCode());
        assertEquals(0x31, parsed.getIdentifier());
        assertArrayEquals(hex("0306C0A80164"), parsed.getOptions());
    }

    @Test
    public void buildIpcpConfigureRequest_ShouldUseIpcpProtocol() throws Exception {
        byte[] packet = PppPacketUtil.buildIpcpConfigureRequest(
            0x32,
            hex("030600000000")
        );

        PppPacketUtil.ConfigurePacket parsed = PppPacketUtil.parseConfigurePacket(
            packet,
            PppConstants.PROTOCOL_IPCP
        );
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, parsed.getCode());
        assertEquals(0x32, parsed.getIdentifier());
        assertArrayEquals(hex("030600000000"), parsed.getOptions());
    }

    @Test
    public void buildLcpEchoReply_ShouldReusePayload() throws Exception {
        byte[] packet = PppPacketUtil.buildLcpEchoReply(0x21, hex("12345678"));

        PppPacketUtil.PppPacket parsed = PppPacketUtil.parse(packet);
        assertEquals(PppConstants.PROTOCOL_LCP, parsed.getProtocol());
        assertEquals(PppConstants.CODE_ECHO_REPLY, parsed.getCode());
        assertEquals(0x21, parsed.getIdentifier());
        assertArrayEquals(hex("12345678"), parsed.getPayload());
    }

    @Test
    public void buildLcpProtocolReject_ShouldIncludeRejectedProtocolAndPayload() throws Exception {
        byte[] packet = PppPacketUtil.buildLcpProtocolReject(
            0x33,
            0x8057,
            hex("01010008")
        );

        PppPacketUtil.PppPacket parsed = PppPacketUtil.parse(packet);
        assertEquals(PppConstants.PROTOCOL_LCP, parsed.getProtocol());
        assertEquals(PppConstants.CODE_PROTOCOL_REJECT, parsed.getCode());
        assertEquals(0x33, parsed.getIdentifier());
        assertArrayEquals(hex("805701010008"), parsed.getPayload());
    }

    @Test
    public void parseChapSuccess_ShouldExposeMessage() throws Exception {
        byte[] frame = hex("C223034500072F4F4B");

        PppPacketUtil.ChapSuccess success = PppPacketUtil.parseChapSuccess(frame);

        assertEquals(0x45, success.getIdentifier());
        assertEquals("/OK", success.getMessage());
    }

    @Test
    public void buildDataFrameForIpPacket_ShouldWrapIpv4Packet() throws Exception {
        byte[] ipPacket = hex("4500001400010000400600000A0000010A000002");

        byte[] frame = PppPacketUtil.buildDataFrameForIpPacket(ipPacket);
        PppPacketUtil.DataFrame parsed = PppPacketUtil.parseDataFrame(frame);

        assertArrayEquals(hex("FF03"), slice(frame, 0, 2));
        assertEquals(PppConstants.PROTOCOL_IPV4, parsed.getProtocol());
        assertArrayEquals(ipPacket, parsed.getPayload());
    }

    @Test
    public void buildDataFrameForIpPacket_ShouldWrapIpv6Packet() throws Exception {
        byte[] ipPacket = hex("6000000000083A4020010DB800000000000000000000000120010DB8000000000000000000000002");

        byte[] frame = PppPacketUtil.buildDataFrameForIpPacket(ipPacket);
        PppPacketUtil.DataFrame parsed = PppPacketUtil.parseDataFrame(frame);

        assertArrayEquals(hex("FF03"), slice(frame, 0, 2));
        assertEquals(PppConstants.PROTOCOL_IPV6, parsed.getProtocol());
        assertArrayEquals(ipPacket, parsed.getPayload());
    }

    private static byte[] slice(byte[] input, int offset, int length) {
        byte[] output = new byte[length];
        System.arraycopy(input, offset, output, 0, length);
        return output;
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int index = i * 2;
            result[i] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
        }
        return result;
    }
}
