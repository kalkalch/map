// Socks5Handler.java
package com.map.proxy;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import timber.log.Timber;

/**
 * SOCKS5 proxy handler implementation.
 * Supports chaining through upstream proxy with credentials.
 */
public class Socks5Handler {
    private static final int SOCKS5_VERSION = 0x05;
    private static final int AUTH_NONE = 0x00;
    private static final int AUTH_USER_PASS = 0x02;
    private static final int CMD_CONNECT = 0x01;
    private static final int ADDR_TYPE_IPV4 = 0x01;
    private static final int ADDR_TYPE_DOMAIN = 0x03;
    private static final int ADDR_TYPE_IPV6 = 0x04;
    
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15000;
    private static final int UPSTREAM_CONNECT_ATTEMPTS = 3;
    private static final int UPSTREAM_RETRY_DELAY_MS = 700;
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_HTTP_LINE_BYTES = 8192;
    
    private final Context context;
    private final int port;
    private ProxyNode upstreamProxy;
    private ProxyNode.ProxyCredentials requiredCredentials;
    
    public enum Auth { NONE, USER_PASSWORD }

    private static final class DestinationAddress {
        private final byte addrType;
        private final String host;
        private final int port;
        private final byte[] rawAddress;

        private DestinationAddress(byte addrType, String host, int port, byte[] rawAddress) {
            this.addrType = addrType;
            this.host = host;
            this.port = port;
            this.rawAddress = rawAddress;
        }

        private InetSocketAddress toSocketAddress() {
            return new InetSocketAddress(host, port);
        }
    }
    
    public Socks5Handler(Context context) {
        this(context, 1080);
    }
    
    public Socks5Handler(Context context, int port) {
        this.context = context;
        this.port = port;
    }
    
    /**
     * Set upstream proxy for chain connections.
     */
    public void setUpstreamProxy(ProxyNode proxy) {
        this.upstreamProxy = proxy;
    }

    public void setRequiredCredentials(ProxyNode.ProxyCredentials credentials) {
        this.requiredCredentials = credentials;
    }
    
    /**
     * Handle SOCKS5 connections from client.
     */
    public void handleConnection(Socket clientSocket) throws IOException {
        clientSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        InputStream in = clientSocket.getInputStream();
        OutputStream out = clientSocket.getOutputStream();
        
        // Read greeting (version + auth methods)
        byte[] greeting = new byte[2];
        if (!readFully(in, greeting, 2) || greeting[0] != SOCKS5_VERSION) {
            clientSocket.close();
            return;
        }
        
        // Skip auth methods offered by client
        int numMethods = greeting[1] & 0xFF;
        byte[] methods = new byte[numMethods];
        if (!readFully(in, methods, numMethods)) {
            clientSocket.close();
            return;
        }

        boolean noAuthOffered = false;
        boolean userPassOffered = false;
        for (byte method : methods) {
            if (method == AUTH_NONE) {
                noAuthOffered = true;
            }
            if (method == AUTH_USER_PASS) {
                userPassOffered = true;
            }
        }

        boolean requiresAuth = requiredCredentials != null && requiredCredentials.isValid();
        if (requiresAuth && !userPassOffered) {
            out.write(new byte[]{SOCKS5_VERSION, (byte) 0xFF});
            out.flush();
            clientSocket.close();
            return;
        }
        if (!requiresAuth && !noAuthOffered) {
            out.write(new byte[]{SOCKS5_VERSION, (byte) 0xFF});
            out.flush();
            clientSocket.close();
            return;
        }

        out.write(new byte[]{SOCKS5_VERSION, (byte) (requiresAuth ? AUTH_USER_PASS : AUTH_NONE)});
        out.flush();

        if (requiresAuth && !authenticateClient(in, out)) {
            clientSocket.close();
            return;
        }
        
        // Read connection request
        byte[] request = new byte[4];
        if (!readFully(in, request, 4)) {
            clientSocket.close();
            return;
        }
        
        if (request[0] != SOCKS5_VERSION || request[1] != CMD_CONNECT) {
            sendErrorResponse(out, (byte) 0x07); // Command not supported
            clientSocket.close();
            return;
        }
        
        // Parse destination address
        DestinationAddress destAddress = parseDestinationAddress(in, request[3]);
        if (destAddress == null) {
            sendErrorResponse(out, (byte) 0x08); // Address type not supported
            clientSocket.close();
            return;
        }

        Timber.tag("Socks5Handler").i(
            "Requested destination: type=%d host=%s port=%d",
            destAddress.addrType,
            destAddress.host,
            destAddress.port
        );
        
        // Connect to destination (directly or through upstream proxy) with reconnect attempts.
        Socket remoteSocket;
        try {
            remoteSocket = connectToDestination(destAddress);
        } catch (IOException e) {
            Timber.tag("Socks5Handler").w(
                e,
                "Failed to connect destination %s:%d via %s",
                destAddress.host,
                destAddress.port,
                upstreamProxy != null ? "upstream proxy" : "direct route"
            );
            sendErrorResponse(out, (byte) 0x05); // Connection refused
            clientSocket.close();
            return;
        }
        
        // Send success response
        sendSuccessResponse(out, remoteSocket);

        // Established tunnel should not be dropped by read timeout.
        clientSocket.setSoTimeout(0);
        remoteSocket.setSoTimeout(0);
        
        // Start bidirectional data relay
        relayData(clientSocket, remoteSocket);
    }

    private boolean authenticateClient(InputStream in, OutputStream out) throws IOException {
        int version = in.read();
        int usernameLength = in.read();
        if (version != 0x01 || usernameLength <= 0) {
            out.write(new byte[]{0x01, 0x01});
            out.flush();
            return false;
        }

        byte[] usernameBytes = new byte[usernameLength];
        if (!readFully(in, usernameBytes, usernameLength)) {
            out.write(new byte[]{0x01, 0x01});
            out.flush();
            return false;
        }

        int passwordLength = in.read();
        if (passwordLength < 0) {
            out.write(new byte[]{0x01, 0x01});
            out.flush();
            return false;
        }

        byte[] passwordBytes = new byte[passwordLength];
        if (!readFully(in, passwordBytes, passwordLength)) {
            out.write(new byte[]{0x01, 0x01});
            out.flush();
            return false;
        }

        String username = new String(usernameBytes, StandardCharsets.UTF_8);
        String password = new String(passwordBytes, StandardCharsets.UTF_8);
        boolean matches =
            requiredCredentials != null
                && requiredCredentials.isValid()
                && requiredCredentials.getUsername().equals(username)
                && requiredCredentials.getPassword().equals(password);

        out.write(new byte[]{0x01, (byte) (matches ? 0x00 : 0x01)});
        out.flush();
        return matches;
    }
    
    /**
     * Connect to destination - directly or through upstream SOCKS5 proxy.
     */
    private Socket connectToDestination(DestinationAddress dest) throws IOException {
        if (upstreamProxy == null) {
            // Direct connection has no chaining layer to reconnect to.
            Socket socket = new Socket();
            socket.connect(dest.toSocketAddress(), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            return socket;
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= UPSTREAM_CONNECT_ATTEMPTS; attempt++) {
            try {
                Socket socket = connectToUpstreamDestinationOnce(dest);
                if (attempt > 1) {
                    Timber.tag("Socks5Handler").i(
                        "Upstream reconnect succeeded on attempt %d/%d for %s:%d",
                        attempt,
                        UPSTREAM_CONNECT_ATTEMPTS,
                        dest.host,
                        dest.port
                    );
                }
                return socket;
            } catch (IOException e) {
                lastError = e;
                Timber.tag("Socks5Handler").w(
                    e,
                    "Upstream connect attempt %d/%d failed for %s:%d",
                    attempt,
                    UPSTREAM_CONNECT_ATTEMPTS,
                    dest.host,
                    dest.port
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

    private Socket connectToUpstreamDestinationOnce(DestinationAddress dest) throws IOException {
        if (upstreamProxy == null) {
            throw new IOException("Upstream proxy is not configured");
        }

        // Connect through upstream proxy
        if (upstreamProxy.getProtocol() == Protocol.SOCKS5) {
            return connectThroughSocks5Proxy(dest);
        } else {
            return connectThroughHttpProxy(dest);
        }
    }
    
    /**
     * Connect through upstream SOCKS5 proxy with optional credentials.
     */
    private Socket connectThroughSocks5Proxy(DestinationAddress dest) throws IOException {
        Socket socket = new Socket();
        socket.connect(upstreamProxy.getAddress(), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        
        // Send greeting with auth methods
        if (upstreamProxy.hasCredentials()) {
            out.write(new byte[]{SOCKS5_VERSION, 0x02, AUTH_NONE, AUTH_USER_PASS});
        } else {
            out.write(new byte[]{SOCKS5_VERSION, 0x01, AUTH_NONE});
        }
        out.flush();
        
        // Read server choice
        byte[] response = new byte[2];
        if (!readFully(in, response, 2)) {
            socket.close();
            throw new IOException("EOF while reading upstream SOCKS5 response");
        }
        
        if (response[0] != SOCKS5_VERSION) {
            socket.close();
            throw new IOException("Invalid SOCKS5 response from upstream");
        }
        
        // Handle authentication if required
        if (response[1] == AUTH_USER_PASS) {
            if (!upstreamProxy.hasCredentials()) {
                socket.close();
                throw new IOException("Upstream proxy requires authentication");
            }
            
            // Send credentials (RFC 1929)
            byte[] authPacket = upstreamProxy.getCredentials().getSocks5AuthPacket();
            out.write(authPacket);
            out.flush();
            
            // Read auth response
            byte[] authResponse = new byte[2];
            if (!readFully(in, authResponse, 2)) {
                socket.close();
                throw new IOException("EOF while reading upstream SOCKS5 auth response");
            }
            if (authResponse[1] != 0x00) {
                socket.close();
                throw new IOException("Upstream proxy authentication failed");
            }
        } else if (response[1] != AUTH_NONE) {
            socket.close();
            throw new IOException("Unsupported auth method: " + response[1]);
        }
        
        // Send CONNECT request
        byte[] connectRequest;
        if (dest.addrType == ADDR_TYPE_IPV4 || dest.addrType == ADDR_TYPE_IPV6) {
            int addrLen = dest.addrType == ADDR_TYPE_IPV4 ? 4 : 16;
            connectRequest = new byte[4 + addrLen + 2];
            connectRequest[0] = SOCKS5_VERSION;
            connectRequest[1] = CMD_CONNECT;
            connectRequest[2] = 0x00; // Reserved
            connectRequest[3] = dest.addrType;
            System.arraycopy(dest.rawAddress, 0, connectRequest, 4, addrLen);
            connectRequest[4 + addrLen] = (byte) (dest.port >> 8);
            connectRequest[5 + addrLen] = (byte) (dest.port & 0xFF);
        } else {
            byte[] hostBytes = dest.host.getBytes(StandardCharsets.UTF_8);
            if (hostBytes.length == 0 || hostBytes.length > 255) {
                socket.close();
                throw new IOException("Invalid destination host length: " + hostBytes.length);
            }
            connectRequest = new byte[7 + hostBytes.length];
            connectRequest[0] = SOCKS5_VERSION;
            connectRequest[1] = CMD_CONNECT;
            connectRequest[2] = 0x00; // Reserved
            connectRequest[3] = ADDR_TYPE_DOMAIN;
            connectRequest[4] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, connectRequest, 5, hostBytes.length);
            connectRequest[5 + hostBytes.length] = (byte) (dest.port >> 8);
            connectRequest[6 + hostBytes.length] = (byte) (dest.port & 0xFF);
        }
        
        out.write(connectRequest);
        out.flush();
        
        // Read connect response
        byte[] connectResponse = new byte[4];
        if (!readFully(in, connectResponse, 4)) {
            socket.close();
            throw new IOException("EOF while reading upstream SOCKS5 connect response");
        }
        
        if (connectResponse[1] != 0x00) {
            socket.close();
            throw new IOException("SOCKS5 connect failed: " + connectResponse[1]);
        }
        
        // Skip bound address
        skipBoundAddress(in, connectResponse[3]);
        
        return socket;
    }
    
    /**
     * Connect through upstream HTTP proxy using CONNECT method.
     */
    private Socket connectThroughHttpProxy(DestinationAddress dest) throws IOException {
        Socket socket = new Socket();
        socket.connect(upstreamProxy.getAddress(), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        
        String connectHost = dest.host;
        if (connectHost.contains(":") && !connectHost.startsWith("[") && !connectHost.endsWith("]")) {
            connectHost = "[" + connectHost + "]";
        }
        String authority = connectHost + ":" + dest.port;

        StringBuilder request = new StringBuilder();
        request.append("CONNECT ")
               .append(authority)
               .append(" HTTP/1.1\r\n");
        request.append("Host: ")
               .append(authority)
               .append("\r\n");
        
        if (upstreamProxy.hasCredentials()) {
            String authHeader = "Basic " + upstreamProxy.getCredentials().getBase64Encoded();
            request.append("Proxy-Authorization: ").append(authHeader).append("\r\n");
        }
        
        request.append("\r\n");
        
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        
        // Read response status line
        String statusLine = readHttpLine(in);
        
        if (statusLine == null || !statusLine.contains(" 200 ")) {
            socket.close();
            throw new IOException("HTTP CONNECT failed: " + statusLine);
        }
        
        // Read headers until an empty line (CRLF CRLF).
        while (true) {
            String line = readHttpLine(in);
            if (line == null) {
                socket.close();
                throw new IOException("HTTP CONNECT failed: unexpected EOF in headers");
            }
            if (line.isEmpty()) {
                break;
            }
        }
        
        return socket;
    }
    
    private DestinationAddress parseDestinationAddress(InputStream in, byte addrType) throws IOException {
        String host;
        int port;
        byte[] rawAddress;
        
        switch (addrType) {
            case ADDR_TYPE_IPV4:
                byte[] ipv4 = new byte[4];
                if (!readFully(in, ipv4, 4)) {
                    return null;
                }
                host = String.format("%d.%d.%d.%d", 
                    ipv4[0] & 0xFF, ipv4[1] & 0xFF, ipv4[2] & 0xFF, ipv4[3] & 0xFF);
                rawAddress = ipv4;
                break;
                
            case ADDR_TYPE_DOMAIN:
                int domainLen = in.read() & 0xFF;
                if (domainLen <= 0) {
                    return null;
                }
                byte[] domain = new byte[domainLen];
                if (!readFully(in, domain, domainLen)) {
                    return null;
                }
                host = new String(domain, StandardCharsets.UTF_8);
                rawAddress = domain;
                break;
                
            case ADDR_TYPE_IPV6:
                byte[] ipv6 = new byte[16];
                if (!readFully(in, ipv6, 16)) {
                    return null;
                }
                host = InetAddress.getByAddress(ipv6).getHostAddress();
                rawAddress = ipv6;
                break;
                
            default:
                return null;
        }
        
        byte[] portBytes = new byte[2];
        if (!readFully(in, portBytes, 2)) {
            return null;
        }
        port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        
        return new DestinationAddress(addrType, host, port, rawAddress);
    }
    
    private void skipBoundAddress(InputStream in, byte addrType) throws IOException {
        switch (addrType) {
            case ADDR_TYPE_IPV4:
                skipFully(in, 4 + 2); // 4 bytes IP + 2 bytes port
                break;
            case ADDR_TYPE_DOMAIN:
                int len = in.read() & 0xFF;
                if (len <= 0) {
                    throw new IOException("Invalid upstream bound domain length");
                }
                skipFully(in, len + 2);
                break;
            case ADDR_TYPE_IPV6:
                skipFully(in, 16 + 2); // 16 bytes IP + 2 bytes port
                break;
            default:
                throw new IOException("Unsupported upstream bound address type: " + addrType);
        }
    }
    
    private void sendErrorResponse(OutputStream out, byte errorCode) throws IOException {
        out.write(new byte[]{SOCKS5_VERSION, errorCode, 0x00, ADDR_TYPE_IPV4, 
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        out.flush();
    }
    
    private void sendSuccessResponse(OutputStream out, Socket remoteSocket) throws IOException {
        // RFC 1928 allows returning 0.0.0.0:0 when bind info is not important for the client.
        // This avoids malformed replies when the underlying socket address is IPv6.
        out.write(new byte[]{SOCKS5_VERSION, 0x00, 0x00, ADDR_TYPE_IPV4,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00});
        out.flush();
    }
    
    private void relayData(Socket client, Socket remote) {
        Thread clientToRemote = new Thread(() -> {
            try {
                pipe(client.getInputStream(), remote.getOutputStream());
            } catch (IOException e) {
                Timber.tag("Socks5Handler").d(e, "Client->Remote relay ended");
            } finally {
                shutdownOutputQuietly(remote);
            }
        }, "SOCKS5-ClientToRemote");

        Thread remoteToClient = new Thread(() -> {
            try {
                pipe(remote.getInputStream(), client.getOutputStream());
            } catch (IOException e) {
                Timber.tag("Socks5Handler").d(e, "Remote->Client relay ended");
            } finally {
                shutdownOutputQuietly(client);
            }
        }, "SOCKS5-RemoteToClient");

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

    private String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (lineBuffer.size() == 0) {
                    return null;
                }
                break;
            }

            if (prev == '\r' && b == '\n') {
                byte[] bytes = lineBuffer.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }

            lineBuffer.write(b);
            if (lineBuffer.size() > MAX_HTTP_LINE_BYTES) {
                throw new IOException("HTTP header line too long");
            }
            prev = b;
        }

        return new String(lineBuffer.toByteArray(), StandardCharsets.ISO_8859_1);
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

    private void skipFully(InputStream in, int bytesToSkip) throws IOException {
        int remaining = bytesToSkip;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("EOF while skipping bytes");
                }
                remaining--;
            } else {
                remaining -= (int) skipped;
            }
        }
    }

    private void shutdownOutputQuietly(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // Ignore shutdown errors during connection teardown.
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Ignore close errors during connection teardown.
        }
    }
    
    public int getPort() {
        return port;
    }
}
