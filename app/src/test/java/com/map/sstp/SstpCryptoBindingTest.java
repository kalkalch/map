package com.map.sstp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.map.sstp.protocol.SstpConstants;
import com.map.sstp.protocol.SstpCryptoBinding;
import java.io.IOException;
import org.junit.Test;

public class SstpCryptoBindingTest {

    @Test
    public void chooseHashProtocol_ShouldPreferSha256() throws Exception {
        int result = SstpCryptoBinding.chooseHashProtocol(
            SstpConstants.CERT_HASH_PROTOCOL_SHA1 | SstpConstants.CERT_HASH_PROTOCOL_SHA256
        );

        assertEquals(SstpConstants.CERT_HASH_PROTOCOL_SHA256, result);
    }

    @Test
    public void chooseHashProtocol_ShouldRejectSha1Only() throws Exception {
        try {
            SstpCryptoBinding.chooseHashProtocol(SstpConstants.CERT_HASH_PROTOCOL_SHA1);
            fail("Expected IOException");
        } catch (IOException expected) {
            assertEquals("SSTP SHA1 crypto binding is not implemented yet", expected.getMessage());
        }
    }
}
