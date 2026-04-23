package com.map.sstp.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.map.sstp.SstpSessionConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SstpHttpsSessionTest {

    @Test
    public void buildRequest_ShouldUseDuplexPostHeaders() {
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            443,
            "user",
            "pass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );

        byte[] request = SstpHttpsSession.buildRequest(
            config,
            "{12345678-1234-1234-1234-1234567890AB}"
        );
        String text = new String(request, StandardCharsets.US_ASCII);

        assertTrue(text.startsWith("SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n"));
        assertTrue(text.contains("Host: vpn.example.com\r\n"));
        assertTrue(text.contains("Content-Length: 18446744073709551615\r\n"));
        assertTrue(text.contains("SSTPCORRELATIONID: {12345678-1234-1234-1234-1234567890AB}\r\n"));
        assertTrue(text.endsWith("\r\n\r\n"));
    }

    @Test
    public void buildRequest_ShouldIncludeNonDefaultPortInHostHeader() {
        SstpSessionConfig config = new SstpSessionConfig(
            "vpn.example.com",
            4443,
            "user",
            "pass",
            false,
            "127.0.0.1",
            32,
            "MAP-SSTP"
        );

        String text = new String(
            SstpHttpsSession.buildRequest(config, "{11111111-1111-1111-1111-111111111111}"),
            StandardCharsets.US_ASCII
        );

        assertTrue(text.contains("Host: vpn.example.com:4443\r\n"));
    }

    @Test
    public void readResponseHeaders_ShouldReturnStatusLine() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(
            ("HTTP/1.1 200 OK\r\n"
                + "Content-Length: 18446744073709551615\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII)
        );

        String statusLine = SstpHttpsSession.readResponseHeaders(in);

        assertEquals("HTTP/1.1 200 OK", statusLine);
    }
}
