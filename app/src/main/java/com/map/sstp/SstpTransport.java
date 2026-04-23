package com.map.sstp;

import android.net.VpnService;

public interface SstpTransport {
    SstpController.Result connect(SstpSessionConfig config, VpnService vpnService);
    void disconnect();
    boolean isAvailable();
}
