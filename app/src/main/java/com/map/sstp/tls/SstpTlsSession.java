package com.map.sstp.tls;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.net.ssl.SSLSocket;

public class SstpTlsSession implements AutoCloseable {
    private final SSLSocket socket;

    public SstpTlsSession(SSLSocket socket) {
        this.socket = socket;
    }

    public SSLSocket getSocket() {
        return socket;
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
