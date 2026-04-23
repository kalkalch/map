package com.map.sstp;

import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Holds runtime resources needed by the SSTP transport.
 */
public class SstpTunnelContext implements AutoCloseable {
    private final ParcelFileDescriptor tunnelInterface;
    private FileInputStream inputStream;
    private FileOutputStream outputStream;

    public SstpTunnelContext(ParcelFileDescriptor tunnelInterface) {
        this.tunnelInterface = tunnelInterface;
    }

    public ParcelFileDescriptor getTunnelInterface() {
        return tunnelInterface;
    }

    public boolean hasTunnelInterface() {
        return tunnelInterface != null && tunnelInterface.getFd() >= 0;
    }

    public synchronized FileInputStream getInputStream() throws IOException {
        ensureTunnelInterface();
        if (inputStream == null) {
            inputStream = new FileInputStream(tunnelInterface.getFileDescriptor());
        }
        return inputStream;
    }

    public synchronized FileOutputStream getOutputStream() throws IOException {
        ensureTunnelInterface();
        if (outputStream == null) {
            outputStream = new FileOutputStream(tunnelInterface.getFileDescriptor());
        }
        return outputStream;
    }

    private void ensureTunnelInterface() throws IOException {
        if (!hasTunnelInterface()) {
            throw new IOException("TUN interface is not available");
        }
    }

    @Override
    public void close() throws IOException {
        IOException closeError = null;
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                closeError = e;
            } finally {
                inputStream = null;
            }
        }
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                if (closeError == null) {
                    closeError = e;
                }
            } finally {
                outputStream = null;
            }
        }
        if (tunnelInterface != null) {
            try {
                tunnelInterface.close();
            } catch (IOException e) {
                if (closeError == null) {
                    closeError = e;
                }
            }
        }
        if (closeError != null) {
            throw closeError;
        }
    }
}
