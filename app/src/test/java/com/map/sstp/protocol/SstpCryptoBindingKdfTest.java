package com.map.sstp.protocol;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/**
 * Regression: CMK derivation must use 16-bit little-endian length (digest size), per MS-SSTP /
 * reference implementations (e.g. sorz/sstp-server).
 */
public class SstpCryptoBindingKdfTest {

    @Test
    public void deriveSha256Cmk_WithZeroHlak_MatchesReferenceVector() throws Exception {
        byte[] hlak = new byte[32];
        byte[] cmk = SstpCryptoBinding.deriveSha256Cmk(hlak);
        assertArrayEquals(
            hex("d342eb00477d6a37e1a184fb0168cb3ea3b6645fa0f227904d20eef5cb8f9327"),
            cmk
        );
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
