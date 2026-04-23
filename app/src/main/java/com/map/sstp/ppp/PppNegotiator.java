package com.map.sstp.ppp;

import com.map.sstp.SstpSessionConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Compatibility wrapper kept for tests and future extraction.
 * Current production flow performs PPP negotiation directly via {@link PppSession}.
 */
public class PppNegotiator {
    private final InputStream in;
    private final OutputStream out;
    private final SstpSessionConfig config;
    private NegotiationState state = NegotiationState.IDLE;

    public PppNegotiator(InputStream in, OutputStream out, SstpSessionConfig config) {
        this.in = in;
        this.out = out;
        this.config = config;
    }

    public NegotiationResult negotiate() throws IOException {
        if (config == null) {
            throw new IOException("Missing PPP negotiation config");
        }
        state = NegotiationState.COMPLETED;
        return new NegotiationResult(
            null,
            null,
            PppSession.zeroedHlak()
        );
    }

    public NegotiationState getState() {
        return state;
    }

    public InputStream getInputStream() {
        return in;
    }

    public OutputStream getOutputStream() {
        return out;
    }

    public SstpSessionConfig getConfig() {
        return config;
    }

    public enum NegotiationState {
        IDLE,
        COMPLETED
    }

    public static final class NegotiationResult {
        private final byte[] assignedIpAddress;
        private final MsChapV2Material msChapV2Material;
        private final byte[] higherLayerAuthKey;

        public NegotiationResult(byte[] assignedIpAddress, MsChapV2Material msChapV2Material,
                                 byte[] higherLayerAuthKey) {
            this.assignedIpAddress = assignedIpAddress != null ? assignedIpAddress.clone() : null;
            this.msChapV2Material = msChapV2Material;
            this.higherLayerAuthKey = higherLayerAuthKey != null ? higherLayerAuthKey.clone() : null;
        }

        public byte[] getAssignedIpAddress() {
            return assignedIpAddress != null ? assignedIpAddress.clone() : null;
        }

        public MsChapV2Material getMsChapV2Material() {
            return msChapV2Material;
        }

        public byte[] getHigherLayerAuthKey() {
            return higherLayerAuthKey != null ? higherLayerAuthKey.clone() : null;
        }
    }
}
