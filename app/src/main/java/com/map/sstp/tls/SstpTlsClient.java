package com.map.sstp.tls;

import android.net.VpnService;
import com.map.sstp.SstpSessionConfig;
import com.map.util.SstpConnectionLog;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Placeholder for TLS bootstrap layer used by SSTP.
 */
public class SstpTlsClient {
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final Pattern IPV4_PATTERN =
        Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private SstpTlsSession activeSession;

    public SstpTlsSession connect(SstpSessionConfig config, VpnService vpnService) throws IOException {
        disconnect();

        SSLSocket socket = (SSLSocket) createSocketFactory(config.isIgnoreCertificateErrors()).createSocket();
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        if (vpnService != null) {
            vpnService.protect(socket);
        }
        applySniIfNeeded(socket, config.getServerHost());
        if (config.isIgnoreCertificateErrors()) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm(null);
            socket.setSSLParameters(parameters);
        }
        socket.connect(new InetSocketAddress(config.getServerHost(), config.getServerPort()), CONNECT_TIMEOUT_MS);
        if (socket.getInetAddress() != null) {
            SstpConnectionLog.log(
                "SstpTlsClient",
                "SSTP resolved endpoint: " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort()
            );
        }
        socket.startHandshake();
        activeSession = new SstpTlsSession(socket);
        return activeSession;
    }

    public void disconnect() {
        if (activeSession != null) {
            try {
                activeSession.close();
            } catch (IOException ignored) {
            } finally {
                activeSession = null;
            }
        }
    }

    public SstpTlsSession getActiveSession() {
        return activeSession;
    }

    public static void applySniIfNeeded(SSLSocket socket, String host) {
        if (socket == null || host == null || host.isEmpty() || !shouldSendSni(host)) {
            return;
        }

        SSLParameters parameters = socket.getSSLParameters();
        parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
        socket.setSSLParameters(parameters);
    }

    public static boolean shouldSendSni(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (IPV4_PATTERN.matcher(host).matches()) {
            return false;
        }
        if (host.contains(":")) {
            return false;
        }
        return true;
    }

    private SSLSocketFactory createSocketFactory(boolean ignoreCertificateErrors) throws IOException {
        if (!ignoreCertificateErrors) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { new PermissiveTrustManager() }, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IOException("Failed to create permissive TLS context", e);
        }
    }

    private static final class PermissiveTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
