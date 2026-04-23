// ProxyNode.java
package com.map.proxy;

import java.net.InetSocketAddress;

/**
 * Represents a single proxy node in a chain.
 * Supports authentication credentials for upstream proxy connections.
 */
public class ProxyNode implements NodeChain {
    private final String host;
    private final int port;
    private final Protocol protocol;
    private final ProxyCredentials credentials;
    
    private NodeChain next;
    
    /**
     * Create proxy node without authentication.
     */
    public ProxyNode(String host, int port, Protocol protocol) {
        this(host, port, protocol, null);
    }
    
    /**
     * Create proxy node with authentication credentials.
     */
    public ProxyNode(String host, int port, Protocol protocol, ProxyCredentials credentials) {
        this.host = host;
        this.port = port;
        this.protocol = protocol;
        this.credentials = credentials;
    }
    
    public String getHost() {
        return host;
    }
    
    public int getPort() {
        return port;
    }
    
    public Protocol getProtocol() {
        return protocol;
    }
    
    public ProxyCredentials getCredentials() {
        return credentials;
    }
    
    public boolean hasCredentials() {
        return credentials != null && credentials.isValid();
    }
    
    public InetSocketAddress getAddress() {
        return new InetSocketAddress(host, port);
    }
    
    @Override
    public NodeChain getNext() {
        return next;
    }
    
    @Override
    public void setNext(NodeChain next) {
        this.next = next;
    }
    
    @Override
    public void process(String destination) {
        // Forward traffic to this proxy node
        // If credentials exist, authenticate first
    }
    
    /**
     * Credentials for proxy authentication.
     */
    public static class ProxyCredentials {
        private final String username;
        private final String password;
        
        public ProxyCredentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
        
        public String getUsername() {
            return username;
        }
        
        public String getPassword() {
            return password;
        }
        
        public boolean isValid() {
            return username != null && !username.isEmpty() 
                && password != null && !password.isEmpty();
        }
        
        /**
         * Get Base64 encoded credentials for HTTP Proxy-Authorization header.
         */
        public String getBase64Encoded() {
            String credentials = username + ":" + password;
            return android.util.Base64.encodeToString(
                credentials.getBytes(), 
                android.util.Base64.NO_WRAP
            );
        }
        
        /**
         * Get credentials formatted for SOCKS5 username/password auth (RFC 1929).
         */
        public byte[] getSocks5AuthPacket() {
            byte[] usernameBytes = username.getBytes();
            byte[] passwordBytes = password.getBytes();
            
            byte[] packet = new byte[3 + usernameBytes.length + passwordBytes.length];
            packet[0] = 0x01; // Version
            packet[1] = (byte) usernameBytes.length;
            System.arraycopy(usernameBytes, 0, packet, 2, usernameBytes.length);
            packet[2 + usernameBytes.length] = (byte) passwordBytes.length;
            System.arraycopy(passwordBytes, 0, packet, 3 + usernameBytes.length, passwordBytes.length);
            
            return packet;
        }
    }
    
    /**
     * Builder for ProxyNode with fluent API.
     */
    public static class Builder {
        private String host;
        private int port = 1080;
        private Protocol protocol = Protocol.SOCKS5;
        private String username;
        private String password;
        
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        public Builder protocol(Protocol protocol) {
            this.protocol = protocol;
            return this;
        }
        
        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }
        
        public ProxyNode build() {
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("Host is required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            
            ProxyCredentials creds = null;
            if (username != null && password != null) {
                creds = new ProxyCredentials(username, password);
            }
            
            return new ProxyNode(host, port, protocol, creds);
        }
    }
}
