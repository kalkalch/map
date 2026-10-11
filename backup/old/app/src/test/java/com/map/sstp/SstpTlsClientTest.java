package com.map.sstp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.map.sstp.SstpSessionConfig;
import com.map.sstp.tls.SstpTlsClient;
import org.junit.Test;

public class SstpTlsClientTest {

    @Test
    public void shouldSendSni_ShouldBeTrueForHostname() {
        assertTrue(SstpTlsClient.shouldSendSni("vpn.example.com"));
    }

    @Test
    public void shouldSendSni_ShouldBeFalseForIpv4() {
        assertFalse(SstpTlsClient.shouldSendSni("192.168.1.10"));
    }

    @Test
    public void shouldSendSni_ShouldBeFalseForIpv6() {
        assertFalse(SstpTlsClient.shouldSendSni("2001:db8::1"));
    }

    @Test
    public void sessionConfig_ShouldStoreIgnoreCertErrorsFlag() {
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "user",
            "pass",
            true,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );

        assertTrue(config.isIgnoreCertificateErrors());
        assertEquals("vpn.example.com", config.getServerHost());
    }
}
