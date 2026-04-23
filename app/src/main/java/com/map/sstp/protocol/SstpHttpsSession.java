package com.map.sstp.protocol;

import com.map.sstp.SstpSessionConfig;
import com.map.sstp.tls.SstpTlsSession;
import com.map.util.SstpConnectionLog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Performs the HTTPS establishment phase required before binary SSTP packets.
 */
public class SstpHttpsSession {
    static final String DUPLEX_METHOD = "SSTP_DUPLEX_POST";
    static final String DUPLEX_PATH = "/sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/";
    static final String INFINITE_CONTENT_LENGTH = "18446744073709551615";
    private static final String TAG = "SstpHttpsSession";
    private static final int MAX_HEADER_BYTES = 8192;

    private boolean established;
    private String correlationId;
    private String responseStatusLine;

    public void establish(SstpSessionConfig config, SstpTlsSession session) throws IOException {
        if (config == null) {
            throw new IOException("Missing SSTP config");
        }
        if (session == null || session.getSocket() == null || session.getSocket().isClosed()) {
            throw new IOException("TLS session is not active");
        }

        correlationId = formatCorrelationId(UUID.randomUUID());
        byte[] requestBytes = buildRequest(config, correlationId);
        OutputStream out = session.getOutputStream();
        out.write(requestBytes);
        out.flush();
        SstpConnectionLog.log(TAG, "Sent HTTPS SSTP duplex request");

        responseStatusLine = readResponseHeaders(session.getInputStream());
        SstpConnectionLog.log(TAG, "Received HTTPS response: " + responseStatusLine);
        if (!responseStatusLine.startsWith("HTTP/1.1 200") && !responseStatusLine.startsWith("HTTP/1.0 200")) {
            throw new IOException("Unexpected HTTPS response for SSTP establish: " + responseStatusLine);
        }
        established = true;
    }

    public void stop() {
        established = false;
        correlationId = null;
        responseStatusLine = null;
    }

    public boolean isEstablished() {
        return established;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getResponseStatusLine() {
        return responseStatusLine;
    }

    static byte[] buildRequest(SstpSessionConfig config, String correlationId) {
        String hostHeader = buildHostHeader(config);
        StringBuilder request = new StringBuilder(256);
        request.append(DUPLEX_METHOD).append(' ').append(DUPLEX_PATH).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(hostHeader).append("\r\n");
        request.append("Content-Length: ").append(INFINITE_CONTENT_LENGTH).append("\r\n");
        request.append("SSTPCORRELATIONID: ").append(correlationId).append("\r\n");
        request.append("\r\n");
        return request.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static String readResponseHeaders(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("HTTPS input stream is not available");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            int next = in.read();
            if (next == -1) {
                throw new IOException("Unexpected EOF while reading HTTPS SSTP response");
            }
            buffer.write(next);
            matched = matchHeaderTerminator(matched, next);
            if (matched == 4) {
                String headerBlock = buffer.toString(StandardCharsets.US_ASCII.name());
                String[] lines = headerBlock.split("\r\n");
                if (lines.length == 0 || lines[0].isBlank()) {
                    throw new IOException("Missing HTTP status line in SSTP establish response");
                }
                return lines[0].trim();
            }
        }

        throw new IOException("HTTPS SSTP response headers exceeded limit");
    }

    private static int matchHeaderTerminator(int current, int next) {
        switch (current) {
            case 0:
            case 2:
                return next == '\r' ? current + 1 : 0;
            case 1:
            case 3:
                return next == '\n' ? current + 1 : 0;
            default:
                return 0;
        }
    }

    private static String buildHostHeader(SstpSessionConfig config) {
        String host = config.getServerHost();
        int port = config.getServerPort();
        if (port <= 0 || port == 443) {
            return host;
        }
        return host + ":" + port;
    }

    private static String formatCorrelationId(UUID uuid) {
        return "{" + uuid.toString().toUpperCase(Locale.US) + "}";
    }
}
