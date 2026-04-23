// HttpsHandler.java
package com.map.proxy;

import java.io.*;
import java.net.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.*;

/**
 * HTTPS proxy handler - transparent SSL/TLS termination.
 * Supports connecting through upstream proxy with credentials.
 */
public class HttpsHandler {
    
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    
    private SSLSocketFactory sslFactory = null;
    private ProxyNode upstreamProxy = null;
    
    public HttpsHandler() {
        initSSLContext();
    }
    
    /**
     * Set upstream proxy for chain connections.
     */
    public void setUpstreamProxy(ProxyNode proxy) {
        this.upstreamProxy = proxy;
    }
    
    /**
     * Handle HTTPS request and forward through proxy chain.
     */
    public void handleHttpsRequest(InetSocketAddress dest) throws IOException {
        SSLSocketFactory factory = getSSLSocketFactory();
        if (factory == null) {
            throw new IOException("SSL context not initialized");
        }
        
        SSLSocket sslSocket = null;
        try {
            if (upstreamProxy != null) {
                // Connect through upstream proxy
                Socket tunnelSocket = connectThroughProxy(dest);
                sslSocket = (SSLSocket) factory.createSocket(
                    tunnelSocket, 
                    dest.getHostString(), 
                    dest.getPort(), 
                    true
                );
            } else {
                // Direct connection
                sslSocket = (SSLSocket) factory.createSocket();
                sslSocket.connect(dest, CONNECT_TIMEOUT_MS);
            }
            
            sslSocket.setSoTimeout(READ_TIMEOUT_MS);
            sslSocket.startHandshake();
            
            // Read and forward request
        } finally {
            if (sslSocket != null && !sslSocket.isClosed()) {
                try {
                    sslSocket.close();
                } catch (IOException ignored) {}
            }
        }
    }
    
    /**
     * Establish tunnel through upstream HTTP proxy using CONNECT method.
     */
    private Socket connectThroughProxy(InetSocketAddress dest) throws IOException {
        Socket socket = new Socket();
        socket.connect(upstreamProxy.getAddress(), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        
        // Build CONNECT request
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ")
               .append(dest.getHostString())
               .append(":")
               .append(dest.getPort())
               .append(" HTTP/1.1\r\n");
        request.append("Host: ")
               .append(dest.getHostString())
               .append(":")
               .append(dest.getPort())
               .append("\r\n");
        
        // Add proxy authentication if credentials exist
        if (upstreamProxy.hasCredentials()) {
            String authHeader = "Basic " + upstreamProxy.getCredentials().getBase64Encoded();
            request.append("Proxy-Authorization: ").append(authHeader).append("\r\n");
        }
        
        request.append("\r\n");
        
        out.write(request.toString().getBytes("UTF-8"));
        out.flush();
        
        // Read response
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String statusLine = reader.readLine();
        
        if (statusLine == null || !statusLine.contains("200")) {
            socket.close();
            throw new IOException("Proxy CONNECT failed: " + statusLine);
        }
        
        // Skip headers until empty line
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // Skip response headers
        }
        
        return socket;
    }
    
    private void initSSLContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, null, null);
            sslFactory = context.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            // Log error, sslFactory remains null
        }
    }
    
    private SSLSocketFactory getSSLSocketFactory() {
        return sslFactory;
    }
}