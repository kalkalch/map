package com.map.sstp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.map.sstp.protocol.SstpConstants;
import com.map.sstp.protocol.SstpPacketUtil;
import org.junit.Test;

public class SstpPacketUtilTest {

    @Test
    public void buildCallConnectRequest_ShouldContainExpectedHeader() throws Exception {
        byte[] packet = SstpPacketUtil.buildCallConnectRequest();
        SstpPacketUtil.ControlHeader header = SstpPacketUtil.parseControlHeader(packet);

        assertEquals(SstpConstants.VERSION_1_0 & 0xFF, header.getVersion());
        assertEquals(SstpConstants.MSG_CALL_CONNECT_REQUEST, header.getMessageType());
        assertEquals(1, header.getAttributeCount());
        assertEquals(14, header.getLength());
    }

    @Test
    public void parseControlHeader_ShouldParseConnectAckHeader() throws Exception {
        byte[] packet = new byte[] {
            0x10, 0x01, 0x00, 0x30, 0x00, 0x02, 0x00, 0x01,
            0x00, 0x04, 0x00, 0x28, 0x00, 0x00, 0x00, 0x02,
            0x41, 0x2B, 0x48, (byte) 0x9A, (byte) 0xEB, (byte) 0xD7, (byte) 0xEC, (byte) 0xC7,
            (byte) 0xD0, (byte) 0x89, 0x66, (byte) 0xF2, 0x6B, (byte) 0xE7, (byte) 0xCD, 0x72,
            (byte) 0xB2, 0x31, (byte) 0xA0, (byte) 0xE9, 0x21, 0x0D, 0x7C, (byte) 0x91,
            (byte) 0xB3, 0x08, (byte) 0x86, 0x2B, 0x03, 0x44, (byte) 0xC4, 0x35
        };

        SstpPacketUtil.ControlHeader header = SstpPacketUtil.parseControlHeader(packet);

        assertEquals(SstpConstants.MSG_CALL_CONNECT_ACK, header.getMessageType());
        assertEquals(48, header.getLength());
        assertEquals(1, header.getAttributeCount());
    }

    @Test
    public void parseCryptoBindingRequest_ShouldParseAckAttribute() throws Exception {
        byte[] packet = new byte[] {
            0x10, 0x01, 0x00, 0x30, 0x00, 0x02, 0x00, 0x01,
            0x00, 0x04, 0x00, 0x28, 0x00, 0x00, 0x00, 0x02,
            0x41, 0x2B, 0x48, (byte) 0x9A, (byte) 0xEB, (byte) 0xD7, (byte) 0xEC, (byte) 0xC7,
            (byte) 0xD0, (byte) 0x89, 0x66, (byte) 0xF2, 0x6B, (byte) 0xE7, (byte) 0xCD, 0x72,
            (byte) 0xB2, 0x31, (byte) 0xA0, (byte) 0xE9, 0x21, 0x0D, 0x7C, (byte) 0x91,
            (byte) 0xB3, 0x08, (byte) 0x86, 0x2B, 0x03, 0x44, (byte) 0xC4, 0x35
        };

        SstpPacketUtil.CryptoBindingRequest request = SstpPacketUtil.parseCryptoBindingRequest(packet);

        assertEquals(SstpConstants.CERT_HASH_PROTOCOL_SHA256, request.getHashProtocolBitmask());
        assertEquals(32, request.getNonce().length);
    }

    @Test
    public void buildCallConnected_ShouldUseNonceAndProtocolFromAck() throws Exception {
        byte[] packet = new byte[] {
            0x10, 0x01, 0x00, 0x30, 0x00, 0x02, 0x00, 0x01,
            0x00, 0x04, 0x00, 0x28, 0x00, 0x00, 0x00, 0x02,
            0x41, 0x2B, 0x48, (byte) 0x9A, (byte) 0xEB, (byte) 0xD7, (byte) 0xEC, (byte) 0xC7,
            (byte) 0xD0, (byte) 0x89, 0x66, (byte) 0xF2, 0x6B, (byte) 0xE7, (byte) 0xCD, 0x72,
            (byte) 0xB2, 0x31, (byte) 0xA0, (byte) 0xE9, 0x21, 0x0D, 0x7C, (byte) 0x91,
            (byte) 0xB3, 0x08, (byte) 0x86, 0x2B, 0x03, 0x44, (byte) 0xC4, 0x35
        };

        SstpPacketUtil.CryptoBindingRequest request = SstpPacketUtil.parseCryptoBindingRequest(packet);
        byte[] certificateHash = new byte[32];
        byte[] compoundMac = new byte[32];
        SstpPacketUtil.CryptoBindingMaterial material = new SstpPacketUtil.CryptoBindingMaterial(
            request.getHashProtocolBitmask(),
            request.getNonce(),
            certificateHash,
            compoundMac
        );
        byte[] callConnected = SstpPacketUtil.buildCallConnected(material);
        SstpPacketUtil.ControlHeader header = SstpPacketUtil.parseControlHeader(callConnected);

        assertEquals(SstpConstants.MSG_CALL_CONNECTED, header.getMessageType());
        assertEquals(SstpConstants.CALL_CONNECTED_TOTAL_LENGTH, header.getLength());
        assertEquals(SstpConstants.CERT_HASH_PROTOCOL_SHA256, callConnected[15] & 0xFF);
        assertEquals(0x41, callConnected[16] & 0xFF);
        assertEquals(0x35, callConnected[47] & 0xFF);
    }

    @Test
    public void buildDataPacket_ShouldPreservePayload() throws Exception {
        byte[] packet = SstpPacketUtil.buildDataPacket(hex("C2230240003A"));

        SstpPacketUtil.DataPacket parsed = SstpPacketUtil.parseDataPacket(packet);

        assertEquals(SstpConstants.VERSION_1_0 & 0xFF, parsed.getVersion());
        assertEquals(10, parsed.getLength());
        assertArrayEquals(hex("C2230240003A"), parsed.getPayload());
    }

    @Test
    public void buildControlPacket_ShouldBuildHeaderOnlyPacket() throws Exception {
        byte[] packet = SstpPacketUtil.buildControlPacket(SstpConstants.MSG_ECHO_RESPONSE);

        SstpPacketUtil.ControlHeader header = SstpPacketUtil.parseControlHeader(packet);

        assertEquals(SstpConstants.MSG_ECHO_RESPONSE, header.getMessageType());
        assertEquals(SstpConstants.CONTROL_HEADER_SIZE, header.getLength());
        assertEquals(0, header.getAttributeCount());
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
