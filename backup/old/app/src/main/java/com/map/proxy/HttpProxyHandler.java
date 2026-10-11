package com.map.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import timber.log.Timber;

/**
 * Minimal HTTP proxy handler with CONNECT tunneling support.
 * Supports optional upstream proxy chaining (HTTP or SOCKS5).
 */
public class HttpProxyHandler {
    private static final String TAG = "HttpProxyHandler";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15000;
    private static final int UPSTREAM_CONNECT_ATTEMPTS = 3;
    private static final int UPSTREAM_RETRY_DELAY_MS = 700;
    private static final int MAX_HTTP_LINE_BYTES = 8192;
    private static final int BUFFER_SIZE = 8192;

    private ProxyNode upstreamProxy;
    private ProxyNode.ProxyCredentials requiredCredentials;

    public void setUpstreamProxy(ProxyNode proxy) {
        this.upstreamProxy = proxy;
    }

    public void setRequiredCredentials(ProxyNode.ProxyCredentials credentials) {
        this.requiredCredentials = credentials;
    }

    public void handleConnection(Socket clientSocket) throws IOException {
        clientSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        InputStream clientIn = clientSocket.getInputStream();
        OutputStream clientOut = clientSocket.getOutputStream();

        ParsedRequest request = readRequest(clientIn);
        if (request == null) {
            return;
        }

        if (requiredCredentials != null && requiredCredentials.isValid() && !isAuthorized(request.proxyAuthorization)) {
            writeProxyAuthRequired(clientOut);
            return;
        }

        if ("CONNECT".equalsIgnoreCase(request.method)) {
            Target target = parseConnectTarget(request.target);
            if (target == null) {
                writeSimpleResponse(clientOut, 400, "Bad Request");
                return;
            }

            Socket remote = connectToTarget(target.host, target.port);
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            clientSocket.setSoTimeout(0);
            remote.setSoTimeout(0);
            relay(clientSocket, remote);
            return;
        }

        Target target = resolveHttpTarget(request);
        if (target == null) {
            writeSimpleResponse(clientOut, 400, "Bad Request");
            return;
        }

        Socket remote = connectToTarget(target.host, target.port);
        OutputStream remoteOut = remote.getOutputStream();

        String outboundTarget = request.target;
        if (upstreamProxy == null || upstreamProxy.getProtocol() == Protocol.SOCKS5) {
            outboundTarget = target.pathAndQuery;
        } else if (!isAbsoluteUri(request.target)) {
            outboundTarget = "http://" + target.authority + target.pathAndQuery;
        }

        String requestLine = request.method + " " + outboundTarget + " " + request.version + "\r\n";
        remoteOut.write(requestLine.getBytes(StandardCharsets.ISO_8859_1));

        boolean hasHost = false;
        for (String header : request.headers) {
            String lower = header.toLowerCase();
            if (lower.startsWith("host:")) {
                hasHost = true;
            }
            if (lower.startsWith("proxy-connection:")) {
                continue;
            }
            if (lower.startsWith("proxy-authorization:")) {
                continue;
            }
            if (lower.startsWith("connection:")) {
                continue;
            }
            remoteOut.write((header + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }

        if (!hasHost) {
            remoteOut.write(("Host: " + target.authority + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        if (upstreamProxy != null && upstreamProxy.getProtocol() == Protocol.HTTP && upstreamProxy.hasCredentials()) {
            String auth = "Basic " + upstreamProxy.getCredentials().getBase64Encoded();
            remoteOut.write(("Proxy-Authorization: " + auth + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        remoteOut.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        remoteOut.flush();

        clientSocket.setSoTimeout(0);
        remote.setSoTimeout(0);
        relay(clientSocket, remote);
    }

    private Socket connectToTarget(String host, int port) throws IOException {
        if (upstreamProxy == null) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            return socket;
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= UPSTREAM_CONNECT_ATTEMPTS; attempt++) {
            try {
                if (upstreamProxy.getProtocol() == Protocol.SOCKS5) {
                    return connectThroughSocks5Upstream(host, port);
                }
                return connectThroughHttpUpstream(host, port);
            } catch (IOException e) {
                lastError = e;
                Timber.tag(TAG).w(
                    e,
                    "Upstream connect attempt %d/%d failed for %s:%d via %s",
                    attempt,
                    UPSTREAM_CONNECT_ATTEMPTS,
                    host,
                    port,
                    upstreamProxy.getProtocol()
                );
                if (attempt < UPSTREAM_CONNECT_ATTEMPTS) {
                    try {
                        Thread.sleep(UPSTREAM_RETRY_DELAY_MS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during upstream reconnect wait", interruptedException);
                    }
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Failed to connect via upstream proxy");
    }

    private Socket connectThroughHttpUpstream(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(upstreamProxy.getAddress(), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

        String authority = formatAuthority(host, port);
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(authority).append("\r\n");
        if (upstreamProxy.hasCredentials()) {
            String auth = "Basic " + upstreamProxy.getCredentials().getBase64Encoded();
            request.append("Proxy-Authorization: ").append(auth).append("\r\n");
        }
        request.append("\r\n");

        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String statusLine = readHttpLine(in);
        if (statusLine == null || !statusLine.contains(" 200 ")) {
            closeQuietly(socket);
            throw new IOException("Upstream HTTP CONNECT failed: " + statusLine);
        }
        while (true) {
            String line = readHttpLine(in);
            if (line == null) {
                closeQuietly(socket);
                throw new IOException("Upstream HTTP CONNECT failed: EOF in headers");
            }
            if (line.isEmpty()) {
                break;
            }
        }
        return socket;
    }

    private Socket connectThroughSocks5Upstream(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(upstreamProxy.getAddress(), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        if (upstreamProxy.hasCredentials()) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        byte[] methodReply = new byte[2];
        if (!readFully(in, methodReply, 2) || methodReply[0] != 0x05) {
            closeQuietly(socket);
            throw new IOException("Invalid upstream SOCKS5 method reply");
        }
        if (methodReply[1] == 0x02) {
            if (!upstreamProxy.hasCredentials()) {
                closeQuietly(socket);
                throw new IOException("Upstream SOCKS5 requires auth");
            }
            out.write(upstreamProxy.getCredentials().getSocks5AuthPacket());
            out.flush();
            byte[] authReply = new byte[2];
            if (!readFully(in, authReply, 2) || authReply[1] != 0x00) {
                closeQuietly(socket);
                throw new IOException("Upstream SOCKS5 auth failed");
            }
        } else if (methodReply[1] != 0x00) {
            closeQuietly(socket);
            throw new IOException("Unsupported upstream SOCKS5 auth method: " + methodReply[1]);
        }

        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length == 0 || hostBytes.length > 255) {
            closeQuietly(socket);
            throw new IOException("Invalid host length for SOCKS5 CONNECT");
        }

        byte[] connectReq = new byte[7 + hostBytes.length];
        connectReq[0] = 0x05;
        connectReq[1] = 0x01;
        connectReq[2] = 0x00;
        connectReq[3] = 0x03;
        connectReq[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.length);
        connectReq[5 + hostBytes.length] = (byte) (port >> 8);
        connectReq[6 + hostBytes.length] = (byte) (port & 0xFF);

        out.write(connectReq);
        out.flush();

        byte[] connectReply = new byte[4];
        if (!readFully(in, connectReply, 4) || connectReply[1] != 0x00) {
            closeQuietly(socket);
            throw new IOException("Upstream SOCKS5 CONNECT failed: " + (connectReply[1] & 0xFF));
        }
        skipSocksAddress(in, connectReply[3]);
        return socket;
    }

    private ParsedRequest readRequest(InputStream in) throws IOException {
        String requestLine = readHttpLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) {
            return null;
        }
        List<String> headers = new ArrayList<>();
        String hostHeader = null;
        String proxyAuthorization = null;
        while (true) {
            String line = readHttpLine(in);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                break;
            }
            headers.add(line);
            if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                hostHeader = line.substring(5).trim();
            }
            if (line.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) {
                proxyAuthorization = line.substring(20).trim();
            }
        }
        return new ParsedRequest(parts[0], parts[1], parts[2], headers, hostHeader, proxyAuthorization);
    }

    private boolean isAuthorized(String proxyAuthorization) {
        if (requiredCredentials == null || !requiredCredentials.isValid()) {
            return true;
        }
        if (proxyAuthorization == null || proxyAuthorization.isEmpty()) {
            return false;
        }

        String[] parts = proxyAuthorization.trim().split("\\s+", 2);
        if (parts.length != 2 || !"basic".equalsIgnoreCase(parts[0])) {
            return false;
        }
        return requiredCredentials.getBase64Encoded().equals(parts[1].trim());
    }

    private String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                return buffer.size() == 0 ? null : new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (prev == '\r' && b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            buffer.write(b);
            if (buffer.size() > MAX_HTTP_LINE_BYTES) {
                throw new IOException("HTTP header line too long");
            }
            prev = b;
        }
    }

    private Target parseConnectTarget(String authority) {
        String host = authority;
        int port = 443;

        if (authority.startsWith("[") && authority.contains("]")) {
            int end = authority.indexOf(']');
            host = authority.substring(1, end);
            if (authority.length() > end + 2 && authority.charAt(end + 1) == ':') {
                port = parsePort(authority.substring(end + 2), 443);
            }
            return new Target(host, port, authority, "/");
        }

        int lastColon = authority.lastIndexOf(':');
        if (lastColon > 0 && authority.indexOf(':') == lastColon) {
            host = authority.substring(0, lastColon);
            port = parsePort(authority.substring(lastColon + 1), 443);
        }
        return new Target(host, port, formatAuthority(host, port), "/");
    }

    private Target resolveHttpTarget(ParsedRequest request) {
        if (isAbsoluteUri(request.target)) {
            try {
                URI uri = URI.create(request.target);
                String host = uri.getHost();
                if (host == null || host.isEmpty()) {
                    return null;
                }
                int port = uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                    path += "?" + uri.getRawQuery();
                }
                return new Target(host, port, formatAuthority(host, port), path);
            } catch (Exception e) {
                Timber.tag(TAG).w(e, "Failed to parse absolute URI target: %s", request.target);
                return null;
            }
        }

        if (request.hostHeader == null || request.hostHeader.isEmpty()) {
            return null;
        }
        Target authorityTarget = parseConnectTarget(request.hostHeader);
        return new Target(authorityTarget.host, authorityTarget.port, authorityTarget.authority, request.target);
    }

    private boolean isAbsoluteUri(String target) {
        return target.startsWith("http://") || target.startsWith("https://");
    }

    private int parsePort(String raw, int fallback) {
        try {
            int port = Integer.parseInt(raw);
            return (port >= 1 && port <= 65535) ? port : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void relay(Socket client, Socket remote) {
        Thread clientToRemote = new Thread(() -> {
            try {
                pipe(client.getInputStream(), remote.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                shutdownOutputQuietly(remote);
            }
        }, "HTTP-ClientToRemote");

        Thread remoteToClient = new Thread(() -> {
            try {
                pipe(remote.getInputStream(), client.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                shutdownOutputQuietly(client);
            }
        }, "HTTP-RemoteToClient");

        clientToRemote.start();
        remoteToClient.start();
        try {
            clientToRemote.join();
            remoteToClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(remote);
        }
    }

    private void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    private void skipSocksAddress(InputStream in, byte type) throws IOException {
        int toSkip;
        if (type == 0x01) {
            toSkip = 4 + 2;
        } else if (type == 0x04) {
            toSkip = 16 + 2;
        } else if (type == 0x03) {
            int len = in.read();
            if (len <= 0) {
                throw new IOException("Invalid SOCKS5 domain len");
            }
            toSkip = len + 2;
        } else {
            throw new IOException("Unsupported SOCKS5 address type: " + type);
        }
        while (toSkip > 0) {
            long skipped = in.skip(toSkip);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("EOF while skipping SOCKS5 address");
                }
                toSkip--;
            } else {
                toSkip -= (int) skipped;
            }
        }
    }

    private boolean readFully(InputStream in, byte[] buffer, int expected) throws IOException {
        int offset = 0;
        while (offset < expected) {
            int read = in.read(buffer, offset, expected - offset);
            if (read == -1) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private void writeSimpleResponse(OutputStream out, int code, String reason) throws IOException {
        String response = "HTTP/1.1 " + code + " " + reason + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void writeProxyAuthRequired(OutputStream out) throws IOException {
        String response =
            "HTTP/1.1 407 Proxy Authentication Required\r\n"
                + "Proxy-Authenticate: Basic realm=\"MAP\"\r\n"
                + "Connection: close\r\n"
                + "Content-Length: 0\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private String formatAuthority(String host, int port) {
        if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    private void shutdownOutputQuietly(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class ParsedRequest {
        private final String method;
        private final String target;
        private final String version;
        private final List<String> headers;
        private final String hostHeader;
        private final String proxyAuthorization;

        private ParsedRequest(
            String method,
            String target,
            String version,
            List<String> headers,
            String hostHeader,
            String proxyAuthorization
        ) {
            this.method = method;
            this.target = target;
            this.version = version;
            this.headers = headers;
            this.hostHeader = hostHeader;
            this.proxyAuthorization = proxyAuthorization;
        }
    }

    private static final class Target {
        private final String host;
        private final int port;
        private final String authority;
        private final String pathAndQuery;

        private Target(String host, int port, String authority, String pathAndQuery) {
            this.host = host;
            this.port = port;
            this.authority = authority;
            this.pathAndQuery = pathAndQuery;
        }
    }
}
