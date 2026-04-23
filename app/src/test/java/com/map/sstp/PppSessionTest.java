package com.map.sstp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.map.sstp.ppp.MsChapV2Material;
import com.map.sstp.ppp.PppConstants;
import com.map.sstp.ppp.PppPacketUtil;
import com.map.sstp.ppp.PppSession;
import java.io.IOException;
import org.junit.Test;

public class PppSessionTest {

    @Test
    public void negotiate_ShouldRequireCredentials() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "",
            "",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );

        try {
            session.negotiate(config);
            fail("Expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("username"));
        }
    }

    @Test
    public void completeMsChapV2_ShouldPopulateHigherLayerAuthKey() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );

        session.negotiate(config);
        MsChapV2Material material = session.completeMsChapV2(
            hex("5B5D7C7D7B3F2F3E3C2C602132262628"),
            hex("21402324255E262A28295F2B3A337C7E")
        );

        assertTrue(session.isNegotiated());
        assertArrayEquals(material.getHigherLayerAuthKey(), session.getHigherLayerAuthKey());
    }

    @Test
    public void negotiate_ShouldNotPretendThatHlakIsReadyBeforeWireExchange() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );

        session.negotiate(config);

        assertTrue(session.isNegotiated());
        assertFalse(session.hasDerivedHigherLayerAuthKey());
    }

    @Test
    public void handleIncoming_ShouldAnswerChapChallengeAndPromoteHlakAfterSuccess() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);

        byte[] lcpAck = session.handleIncoming(
            PppPacketUtil.buildLcpConfigureRequest(0x10, hex("0305C22381"))
        );
        assertNotNull(lcpAck);

        session.createInitialLcpConfigureRequest();
        byte[] peerLcpAck = PppPacketUtil.buildConfigureAck(
            PppConstants.PROTOCOL_LCP,
            0x01,
            hex("0305C22381")
        );
        session.handleIncoming(peerLcpAck);

        byte[] chapResponse = session.handleIncoming(
            hex("C2230140001D105B5D7C7D7B3F2F3E3C2C6021322626284D696B726F54696B")
        );
        assertNotNull(chapResponse);
        PppPacketUtil.PppPacket parsedResponse = PppPacketUtil.parse(chapResponse);
        assertEquals(PppConstants.PROTOCOL_CHAP, parsedResponse.getProtocol());
        assertEquals(PppConstants.CHAP_CODE_RESPONSE, parsedResponse.getCode());

        session.handleIncoming(
            hex("C223034000072F4F4B")
        );

        byte[] clientIpcpRequest = session.createIpcpConfigureRequest();
        PppPacketUtil.PppPacket parsedClientIpcp = PppPacketUtil.parse(clientIpcpRequest);
        int clientIpcpIdentifier = parsedClientIpcp.getIdentifier();

        byte[] ipcpPeerAck = session.handleIncoming(
            hex("80210150000A0306C0A80164")
        );
        assertNotNull(ipcpPeerAck);
        assertFalse(session.isReadyForTunnel());

        session.handleIncoming(
            PppPacketUtil.buildConfigureAck(
                PppConstants.PROTOCOL_IPCP,
                clientIpcpIdentifier,
                hex("0306C0A80164")
            )
        );

        assertTrue(session.isChapAuthenticated());
        assertTrue(session.hasDerivedHigherLayerAuthKey());
        assertTrue(session.isIpcpAcknowledged());
        assertArrayEquals(hex("C0A80164"), session.getAssignedIpv4Address());
        assertArrayEquals(hex("C0A80164"), session.getPeerIpv4Address());
        assertTrue(session.isReadyForTunnel());
    }

    @Test
    public void handleIncoming_ShouldRetryAfterLcpNak() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);

        byte[] retry = session.handleIncoming(
            hex("C021030100090305C22381")
        );

        assertNotNull(retry);
        PppPacketUtil.ConfigurePacket parsed = PppPacketUtil.parseConfigurePacket(retry, PppConstants.PROTOCOL_LCP);
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, parsed.getCode());
        assertArrayEquals(hex("0305C22381"), parsed.getOptions());
    }

    @Test
    public void handleIncoming_ShouldRetryWithEmptyLcpRequestAfterReject() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);

        byte[] retry = session.handleIncoming(
            hex("C021040100090305C22381")
        );

        assertNotNull(retry);
        PppPacketUtil.ConfigurePacket parsed = PppPacketUtil.parseConfigurePacket(retry, PppConstants.PROTOCOL_LCP);
        assertEquals(PppConstants.CODE_CONFIGURE_REQUEST, parsed.getCode());
        assertArrayEquals(new byte[0], parsed.getOptions());
    }

    @Test
    public void handleIncoming_ShouldReplyToLcpEchoRequest() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);

        byte[] reply = session.handleIncoming(
            hex("C0210911000812345678")
        );

        assertNotNull(reply);
        PppPacketUtil.PppPacket parsed = PppPacketUtil.parse(reply);
        assertEquals(PppConstants.PROTOCOL_LCP, parsed.getProtocol());
        assertEquals(PppConstants.CODE_ECHO_REPLY, parsed.getCode());
        assertArrayEquals(hex("12345678"), parsed.getPayload());
    }

    @Test
    public void handleIncoming_ShouldCaptureIpcpAssignedAddressFromNak() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);

        byte[] retry = session.handleIncoming(
            hex("80210322000A03060A000001")
        );

        assertNotNull(retry);
        PppPacketUtil.ConfigurePacket parsed = PppPacketUtil.parseConfigurePacket(retry, PppConstants.PROTOCOL_IPCP);
        assertArrayEquals(hex("03060A000001"), parsed.getOptions());
        assertArrayEquals(hex("0A000001"), session.getAssignedIpv4Address());
    }

    @Test
    public void handleIncoming_ShouldRejectUnexpectedIpcpAckIdentifier() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);
        session.createIpcpConfigureRequest();

        try {
            session.handleIncoming(
                PppPacketUtil.buildConfigureAck(
                    PppConstants.PROTOCOL_IPCP,
                    0x7F,
                    hex("03060A000001")
                )
            );
            fail("Expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unexpected IPCP Configure-Ack identifier"));
        }
    }

    @Test
    public void handleIncoming_ShouldAckIpv6cpConfigureRequest() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);

        byte[] ipv6cpReq = PppPacketUtil.buildConfigureRequest(
            PppConstants.PROTOCOL_IPV6CP,
            0x42,
            new byte[0]
        );
        byte[] reply = session.handleIncoming(ipv6cpReq);
        assertNotNull(reply);
        PppPacketUtil.PppPacket parsed = PppPacketUtil.parse(reply);
        assertEquals(PppConstants.PROTOCOL_IPV6CP, parsed.getProtocol());
        assertEquals(PppConstants.CODE_CONFIGURE_ACK, parsed.getCode());
        assertEquals(0x42, parsed.getIdentifier());
    }

    @Test
    public void handleIncoming_ShouldRejectCcpConfigureRequest() throws Exception {
        PppSession session = new PppSession();
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "User",
            "clientPass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );
        session.negotiate(config);

        byte[] ccpReq = PppPacketUtil.buildConfigureRequest(
            PppConstants.PROTOCOL_CCP,
            0x55,
            hex("0101")
        );
        byte[] reply = session.handleIncoming(ccpReq);
        assertNotNull(reply);
        PppPacketUtil.PppPacket parsed = PppPacketUtil.parse(reply);
        assertEquals(PppConstants.PROTOCOL_CCP, parsed.getProtocol());
        assertEquals(PppConstants.CODE_CONFIGURE_REJECT, parsed.getCode());
        assertEquals(0x55, parsed.getIdentifier());
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
