package com.map.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ProxyIntegrationTest {

    @Test(timeout = 15000)
    public void socks5Direct_ShouldHandshakeAndRelay() throws Exception {
        EchoServer echo = EchoServer.start();
        ProxyServer proxy = new ProxyServer(null);
        int proxyPort = reservePort();
        proxy.start("127.0.0.1", proxyPort, Protocol.SOCKS5, null, null);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();

            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();

            byte[] methodReply = new byte[2];
            readFully(in, methodReply);
            assertEquals(0x05, methodReply[0] & 0xFF);
            assertEquals(0x00, methodReply[1] & 0xFF);

            byte[] hostBytes = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            byte[] connect = new byte[7 + hostBytes.length];
            connect[0] = 0x05;
            connect[1] = 0x01;
            connect[2] = 0x00;
            connect[3] = 0x03;
            connect[4] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, connect, 5, hostBytes.length);
            connect[5 + hostBytes.length] = (byte) (echo.port >> 8);
            connect[6 + hostBytes.length] = (byte) (echo.port & 0xFF);
            out.write(connect);
            out.flush();

            byte[] connectReply = new byte[10];
            readFully(in, connectReply);
            assertEquals(0x00, connectReply[1] & 0xFF);

            out.write("ping".getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] resp = new byte[4];
            readFully(in, resp);
            assertEquals("pong", new String(resp, StandardCharsets.UTF_8));
        } finally {
            proxy.stop();
            echo.close();
        }
    }

    @Test(timeout = 15000)
    public void httpConnectDirect_ShouldTunnelTraffic() throws Exception {
        EchoServer echo = EchoServer.start();
        ProxyServer proxy = new ProxyServer(null);
        int proxyPort = reservePort();
        proxy.start("127.0.0.1", proxyPort, Protocol.HTTP, null, null);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();

            String req = "CONNECT 127.0.0.1:" + echo.port + " HTTP/1.1\r\nHost: 127.0.0.1:" + echo.port + "\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String status = readLine(in);
            assertNotNull(status);
            assertTrue(status.contains("200"));
            consumeHeaders(in);

            out.write("ping".getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] resp = new byte[4];
            readFully(in, resp);
            assertEquals("pong", new String(resp, StandardCharsets.UTF_8));
        } finally {
            proxy.stop();
            echo.close();
        }
    }

    @Test(timeout = 20000)
    public void socks5CascadeViaHttpUpstream_ShouldPreserveDestination() throws Exception {
        EchoServer echo = EchoServer.start();
        UpstreamHttpTunnel upstream = UpstreamHttpTunnel.start(echo.port);

        List<ProxyNode> chain = new ArrayList<>();
        chain.add(new ProxyNode("127.0.0.1", upstream.port, Protocol.HTTP));

        ProxyServer proxy = new ProxyServer(null);
        int proxyPort = reservePort();
        proxy.start("127.0.0.1", proxyPort, Protocol.SOCKS5, chain, null);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();

            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] methodReply = new byte[2];
            readFully(in, methodReply);
            assertEquals(0x00, methodReply[1] & 0xFF);

            byte[] hostBytes = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            byte[] connect = new byte[7 + hostBytes.length];
            connect[0] = 0x05;
            connect[1] = 0x01;
            connect[2] = 0x00;
            connect[3] = 0x03;
            connect[4] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, connect, 5, hostBytes.length);
            connect[5 + hostBytes.length] = (byte) (echo.port >> 8);
            connect[6 + hostBytes.length] = (byte) (echo.port & 0xFF);
            out.write(connect);
            out.flush();

            byte[] connectReply = new byte[10];
            readFully(in, connectReply);
            assertEquals(0x00, connectReply[1] & 0xFF);

            out.write("ping".getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] resp = new byte[4];
            readFully(in, resp);
            assertEquals("pong", new String(resp, StandardCharsets.UTF_8));

            String upstreamConnect = waitForValue(upstream.lastConnectLine, 2000);
            assertNotNull(upstreamConnect);
            assertTrue(upstreamConnect.contains("CONNECT 127.0.0.1:" + echo.port));
            assertTrue(!upstreamConnect.contains("0.0.0.0"));
        } finally {
            proxy.stop();
            upstream.close();
            echo.close();
        }
    }

    @Test(timeout = 15000)
    public void socks5LocalAuth_ShouldRequireCredentials() throws Exception {
        EchoServer echo = EchoServer.start();
        ProxyServer proxy = new ProxyServer(null);
        int proxyPort = reservePort();
        ProxyNode.ProxyCredentials credentials = new ProxyNode.ProxyCredentials("local", "secret");
        proxy.start("127.0.0.1", proxyPort, Protocol.SOCKS5, null, credentials);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();

            out.write(new byte[]{0x05, 0x01, 0x02});
            out.flush();

            byte[] methodReply = new byte[2];
            readFully(in, methodReply);
            assertEquals(0x05, methodReply[0] & 0xFF);
            assertEquals(0x02, methodReply[1] & 0xFF);

            out.write(credentials.getSocks5AuthPacket());
            out.flush();

            byte[] authReply = new byte[2];
            readFully(in, authReply);
            assertEquals(0x00, authReply[1] & 0xFF);

            byte[] hostBytes = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            byte[] connect = new byte[7 + hostBytes.length];
            connect[0] = 0x05;
            connect[1] = 0x01;
            connect[2] = 0x00;
            connect[3] = 0x03;
            connect[4] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, connect, 5, hostBytes.length);
            connect[5 + hostBytes.length] = (byte) (echo.port >> 8);
            connect[6 + hostBytes.length] = (byte) (echo.port & 0xFF);
            out.write(connect);
            out.flush();

            byte[] connectReply = new byte[10];
            readFully(in, connectReply);
            assertEquals(0x00, connectReply[1] & 0xFF);
        } finally {
            proxy.stop();
            echo.close();
        }
    }

    @Test(timeout = 15000)
    public void httpLocalAuth_ShouldRejectMissingCredentials() throws Exception {
        ProxyServer proxy = new ProxyServer(null);
        int proxyPort = reservePort();
        ProxyNode.ProxyCredentials credentials = new ProxyNode.ProxyCredentials("local", "secret");
        proxy.start("127.0.0.1", proxyPort, Protocol.HTTP, null, credentials);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();

            String req = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String status = readLine(in);
            assertNotNull(status);
            assertTrue(status.contains("407"));
        } finally {
            proxy.stop();
        }
    }

    private static int reservePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int read = in.read(buf, off, buf.length - off);
            if (read == -1) {
                throw new IOException("Unexpected EOF");
            }
            off += read;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                return out.size() == 0 ? null : out.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (prev == '\r' && b == '\n') {
                byte[] bytes = out.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            out.write(b);
            prev = b;
        }
    }

    private static void consumeHeaders(InputStream in) throws IOException {
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                return;
            }
        }
    }

    private static void relayAndWait(Socket left, Socket right) {
        Thread a = new Thread(() -> pipe(left, right));
        Thread b = new Thread(() -> pipe(right, left));
        a.start();
        b.start();
        try {
            a.join();
            b.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String waitForValue(AtomicReference<String> ref, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String value = ref.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ref.get();
            }
        }
        return ref.get();
    }

    private static void pipe(Socket inSocket, Socket outSocket) {
        try (InputStream in = inSocket.getInputStream(); OutputStream out = outSocket.getOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final int port;

        private EchoServer(ServerSocket serverSocket, Thread thread) {
            this.serverSocket = serverSocket;
            this.thread = thread;
            this.port = serverSocket.getLocalPort();
        }

        static EchoServer start() throws IOException {
            ServerSocket server = new ServerSocket(0);
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        byte[] req = new byte[4];
                        readFully(s.getInputStream(), req);
                        s.getOutputStream().write("pong".getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().flush();
                    } catch (IOException ignored) {
                    }
                }
            }, "EchoServer");
            t.setDaemon(true);
            t.start();
            return new EchoServer(server, t);
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class UpstreamHttpTunnel implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final int port;
        private final AtomicReference<String> lastConnectLine;

        private UpstreamHttpTunnel(ServerSocket serverSocket, Thread thread, AtomicReference<String> lastConnectLine) {
            this.serverSocket = serverSocket;
            this.thread = thread;
            this.port = serverSocket.getLocalPort();
            this.lastConnectLine = lastConnectLine;
        }

        static UpstreamHttpTunnel start(int destinationPort) throws IOException {
            ServerSocket server = new ServerSocket(0);
            AtomicReference<String> connectRef = new AtomicReference<>();
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket upstreamClient = server.accept();
                         Socket destination = new Socket("127.0.0.1", destinationPort)) {
                        InputStream in = upstreamClient.getInputStream();
                        OutputStream out = upstreamClient.getOutputStream();

                        String connectLine = readLine(in);
                        connectRef.set(connectLine);
                        consumeHeaders(in);

                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();

                        relayAndWait(upstreamClient, destination);
                    } catch (IOException ignored) {
                    }
                }
            }, "UpstreamHttpTunnel");
            UpstreamHttpTunnel tunnel = new UpstreamHttpTunnel(server, t, connectRef);
            t.setDaemon(true);
            t.start();
            return tunnel;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
