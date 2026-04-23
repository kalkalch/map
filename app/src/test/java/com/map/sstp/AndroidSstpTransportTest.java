package com.map.sstp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.map.util.LocalSettings;
import org.junit.Test;

public class AndroidSstpTransportTest {

    @Test
    public void initialState_ShouldBeIdle() {
        AndroidSstpTransport transport = new AndroidSstpTransport();

        assertEquals(AndroidSstpTransport.Stage.IDLE, transport.getStage());
        assertFalse(transport.isConnected());
        assertNull(transport.getActiveConfig());
    }

    @Test
    public void connect_WithNullVpnService_ShouldReturnFailure() {
        AndroidSstpTransport transport = new AndroidSstpTransport();
        SstpSessionConfig config = new SstpSessionConfig(
            "test.server.com", 443, "user", "pass",
            false, "10.0.0.1", 32, "MAP-SSTP"
        );

        SstpController.Result result = transport.connect(config, null);

        assertFalse(result.isTunnelReady());
        assertEquals(LocalSettings.STATUS_ERROR, result.getStatus());
        assertTrue(result.getDetail().contains("VPN service"));
    }

    @Test
    public void connect_WithNullConfig_ShouldReturnFailure() {
        AndroidSstpTransport transport = new AndroidSstpTransport();

        SstpController.Result result = transport.connect(null, null);

        assertFalse(result.isTunnelReady());
    }

    @Test
    public void connect_WithMissingHost_ShouldReturnFailure() {
        AndroidSstpTransport transport = new AndroidSstpTransport();
        SstpSessionConfig config = new SstpSessionConfig(
            "", 443, "user", "pass",
            false, "10.0.0.1", 32, "MAP-SSTP"
        );

        SstpController.Result result = transport.connect(config, null);

        assertFalse(result.isTunnelReady());
        assertTrue(result.getDetail().contains("host") || result.getDetail().contains("VPN"));
    }

    @Test
    public void connect_WithMissingUsername_ShouldReturnFailure() {
        AndroidSstpTransport transport = new AndroidSstpTransport();
        SstpSessionConfig config = new SstpSessionConfig(
            "test.server.com", 443, "", "pass",
            false, "10.0.0.1", 32, "MAP-SSTP"
        );

        SstpController.Result result = transport.connect(config, null);

        assertFalse(result.isTunnelReady());
    }

    @Test
    public void disconnect_ShouldResetState() {
        AndroidSstpTransport transport = new AndroidSstpTransport();
        transport.disconnect();

        assertEquals(AndroidSstpTransport.Stage.IDLE, transport.getStage());
        assertFalse(transport.isConnected());
        assertNull(transport.getActiveConfig());
    }

    @Test
    public void isAvailable_ShouldReturnTrue() {
        AndroidSstpTransport transport = new AndroidSstpTransport();
        assertTrue(transport.isAvailable());
    }

    @Test
    public void stageEnum_ShouldHaveAllStages() {
        AndroidSstpTransport.Stage[] stages = AndroidSstpTransport.Stage.values();
        assertEquals(10, stages.length);

        boolean found = false;
        for (AndroidSstpTransport.Stage stage : stages) {
            if (stage == AndroidSstpTransport.Stage.STARTING_RELAY) {
                found = true;
                break;
            }
        }
        assertTrue("Missing STARTING_RELAY stage", found);
    }

    @Test
    public void resultClass_ShouldStoreValues() {
        SstpController.Result result = new SstpController.Result(
            LocalSettings.STATUS_CONNECTED,
            true,
            "Test detail"
        );

        assertEquals(LocalSettings.STATUS_CONNECTED, result.getStatus());
        assertTrue(result.isTunnelReady());
        assertEquals("Test detail", result.getDetail());
    }
}
