package com.map.sstp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.map.sstp.ppp.PppConstants;
import com.map.sstp.ppp.PppNegotiator;
import com.map.sstp.ppp.PppPacketUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.Test;

public class PppNegotiatorTest {

    @Test
    public void negotiationResult_ShouldContainValidHlak() throws Exception {
        byte[] hlak = new byte[32];
        for (int i = 0; i < hlak.length; i++) {
            hlak[i] = (byte) i;
        }

        PppNegotiator.NegotiationResult result = new PppNegotiator.NegotiationResult(
            new byte[] { 10, 0, 0, 1 },
            null,
            hlak
        );

        assertNotNull(result.getHigherLayerAuthKey());
        assertEquals(32, result.getHigherLayerAuthKey().length);
        assertNotNull(result.getAssignedIpAddress());
        assertEquals(4, result.getAssignedIpAddress().length);
    }

    @Test
    public void negotiatorState_ShouldStartAsIdle() throws Exception {
        SstpSessionConfig config = new SstpSessionConfig(
            "test.server.com", 443, "testuser", "testpassword",
            false, "10.0.0.1", 32, "MAP-SSTP"
        );

        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        PppNegotiator negotiator = new PppNegotiator(in, out, config);
        assertEquals(PppNegotiator.NegotiationState.IDLE, negotiator.getState());
    }

    @Test
    public void lcpOptionsParser_ShouldHandleMruAndMagic() throws Exception {
        // MRU: type=01, len=04, data=05DC (1500)
        // Magic: type=05, len=06, data=AABBCCDD
        byte[] options = hex("010405DC0506AABBCCDD");
        List<PppPacketUtil.PppOption> parsed = PppPacketUtil.parseOptions(options);

        assertEquals(2, parsed.size());
        assertEquals(0x01, parsed.get(0).getType());
        assertEquals(0x05, parsed.get(1).getType());
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
