// ProxyServer.java
package com.map.proxy;

import android.content.Context;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import timber.log.Timber;

/**
 * Main Proxy Server handling HTTP, HTTPS, and SOCKS5 connections.
 * Supports proxy chaining for advanced routing with credentials.
 */
public class ProxyServer {
    private static final String TAG = "ProxyServer";
    private static final int DEFAULT_PORT = 1080;
    private static final int BACKLOG = 50;
    private static final int THREAD_POOL_SIZE = 10;
    
    private final Context context;
    private int port = DEFAULT_PORT;
    private Protocol protocol = Protocol.SOCKS5;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private Thread acceptThread;
    
    private ChainProxy proxyChain;
    private Socks5Handler socks5Handler;
    private HttpProxyHandler httpProxyHandler;
    private ProxyNode.ProxyCredentials localCredentials;
    
    /**
     * Initialize proxy server.
     * @param context Application context.
     */
    public ProxyServer(Context context) {
        this.context = context;
    }
    
    /**
     * Start the proxy server on specified port and bind address.
     * 
     * @param bindAddress Address to bind to (127.0.0.1 for localhost, 0.0.0.0 for all interfaces)
     * @param port Server port (1-65535)
     * @param protocol Protocol to use
     * @param chain Optional list of upstream proxies for chaining
     */
    public synchronized void start(
        String bindAddress,
        int port,
        Protocol protocol,
        List<ProxyNode> chain,
        ProxyNode.ProxyCredentials localCredentials
    ) throws IOException {
        if (isRunning.get()) {
            Timber.tag(TAG).w("Server already running on port %d", this.port);
            return;
        }
        
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        
        this.port = port;
        this.protocol = protocol;
        this.localCredentials = localCredentials;
        
        // Setup proxy chain if provided
        if (chain != null && !chain.isEmpty()) {
            this.proxyChain = ChainProxy.fromList(chain);
            setupHandlersWithChain();
        } else {
            this.proxyChain = null;
            setupHandlers();
        }
        
        // Create server socket
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        
        InetSocketAddress bindAddr;
        if (bindAddress == null || bindAddress.isEmpty()) {
            bindAddr = new InetSocketAddress("127.0.0.1", port);
        } else {
            bindAddr = new InetSocketAddress(bindAddress, port);
        }
        
        serverSocket.bind(bindAddr, BACKLOG);
        
        // Create thread pool for handling connections
        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        
        isRunning.set(true);
        
        // Start accept thread
        acceptThread = new Thread(this::acceptLoop, "ProxyServer-Accept");
        acceptThread.start();
        
        Timber.tag(TAG).i("Proxy server started on %s:%d with protocol %s", bindAddress, port, protocol);
    }
    
    /**
     * Setup handlers without proxy chain.
     */
    private void setupHandlers() {
        socks5Handler = new Socks5Handler(context, port);
        socks5Handler.setRequiredCredentials(localCredentials);
        httpProxyHandler = new HttpProxyHandler();
        httpProxyHandler.setRequiredCredentials(localCredentials);
    }
    
    /**
     * Setup handlers with upstream proxy chain.
     */
    private void setupHandlersWithChain() {
        ProxyNode upstreamProxy = proxyChain.getHead();
        
        socks5Handler = new Socks5Handler(context, port);
        socks5Handler.setRequiredCredentials(localCredentials);
        socks5Handler.setUpstreamProxy(upstreamProxy);
        
        httpProxyHandler = new HttpProxyHandler();
        httpProxyHandler.setRequiredCredentials(localCredentials);
        httpProxyHandler.setUpstreamProxy(upstreamProxy);
    }
    
    /**
     * Main accept loop - handles incoming connections.
     */
    private void acceptLoop() {
        while (isRunning.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                Timber.tag(TAG).i(
                    "Accepted %s connection from %s:%d",
                    protocol,
                    clientSocket.getInetAddress().getHostAddress(),
                    clientSocket.getPort()
                );
                executorService.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (isRunning.get()) {
                    Timber.tag(TAG).e(e, "Error accepting connection");
                }
            }
        }
    }
    
    /**
     * Handle individual client connection.
     */
    private void handleConnection(Socket clientSocket) {
        try {
            switch (protocol) {
                case SOCKS5:
                    socks5Handler.handleConnection(clientSocket);
                    break;
                case HTTP:
                case HTTPS:
                    httpProxyHandler.handleConnection(clientSocket);
                    break;
            }
        } catch (IOException e) {
            Timber.tag(TAG).e(e, "Error handling connection");
        } finally {
            closeQuietly(clientSocket);
        }
    }
    
    /**
     * Stop the proxy server.
     */
    public synchronized void stop() {
        if (!isRunning.getAndSet(false)) {
            return;
        }
        
        // Close server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Timber.tag(TAG).e(e, "Error closing server socket");
            }
        }
        
        // Shutdown executor
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        // Interrupt accept thread
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        
        Timber.tag(TAG).i("Proxy server stopped");
    }
    
    /**
     * Check if server is running.
     */
    public boolean isRunning() {
        return isRunning.get();
    }
    
    /**
     * Get current server port.
     */
    public int getPort() {
        return port;
    }
    
    /**
     * Get current protocol.
     */
    public Protocol getProtocol() {
        return protocol;
    }
    
    /**
     * Check if proxy chain is configured.
     */
    public boolean hasProxyChain() {
        return proxyChain != null && !proxyChain.isEmpty();
    }
    
    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
