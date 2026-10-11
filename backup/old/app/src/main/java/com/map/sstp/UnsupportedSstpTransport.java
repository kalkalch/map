package com.map.sstp;

import android.net.VpnService;
import com.map.util.LocalSettings;

public class UnsupportedSstpTransport implements SstpTransport {
    @Override
    public SstpController.Result connect(SstpSessionConfig config, VpnService vpnService) {
        return new SstpController.Result(
            LocalSettings.STATUS_UNSUPPORTED,
            false,
            "В проекте еще нет native SSTP/PPP transport"
        );
    }

    @Override
    public void disconnect() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
