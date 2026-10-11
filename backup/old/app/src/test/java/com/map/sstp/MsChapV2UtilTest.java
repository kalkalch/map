package com.map.sstp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.map.sstp.ppp.MsChapV2Material;
import com.map.sstp.ppp.MsChapV2Util;
import org.junit.Test;

public class MsChapV2UtilTest {
    private static final byte[] AUTHENTICATOR_CHALLENGE = hex(
        "5B5D7C7D7B3F2F3E3C2C602132262628"
    );
    private static final byte[] PEER_CHALLENGE = hex(
        "21402324255E262A28295F2B3A337C7E"
    );

    @Test
    public void deriveMaterial_ShouldMatchRfc2759And3079Vectors() throws Exception {
        MsChapV2Material material = MsChapV2Util.deriveMaterial(
            "User",
            "clientPass",
            AUTHENTICATOR_CHALLENGE,
            PEER_CHALLENGE
        );

        assertArrayEquals(hex("D02E4386BCE91226"), material.getChallenge());
        assertArrayEquals(hex("44EBBA8D5312B8D611474411F56989AE"), material.getPasswordHash());
        assertArrayEquals(
            hex("82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF"),
            material.getNtResponse()
        );
        assertArrayEquals(hex("41C00C584BD2D91C4017A2A12FA59F3F"), material.getPasswordHashHash());
        assertEquals(
            "S=407A5589115FD0D6209F510FE9C04566932CDA56",
            material.getAuthenticatorResponse()
        );
        assertArrayEquals(hex("FDECE3717A8C838CB388E527AE3CDD31"), material.getMasterKey());
        assertArrayEquals(
            hex("D5F0E9521E3EA9589645E86051C82226"),
            material.getMasterSendKey()
        );
        assertArrayEquals(
            hex("8B7CDC149B993A1BA118CB153F56DCCB"),
            material.getMasterReceiveKey()
        );
        assertArrayEquals(
            hex("D5F0E9521E3EA9589645E86051C822268B7CDC149B993A1BA118CB153F56DCCB"),
            material.getHigherLayerAuthKey()
        );
        assertEquals(32, material.getHigherLayerAuthKey().length);
    }

    @Test
    public void stripDomain_ShouldReturnBareUsername() {
        assertEquals("user", MsChapV2Util.stripDomain("DOMAIN\\user"));
        assertEquals("user@example.com", MsChapV2Util.stripDomain("user@example.com"));
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
