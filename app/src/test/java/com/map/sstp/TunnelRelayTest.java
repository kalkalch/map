package com.map.sstp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.map.sstp.io.TunnelRelay;
import com.map.sstp.ppp.PppSession;
import org.junit.Test;

public class TunnelRelayTest {

    @Test
    public void initialState_ShouldNotBeRunning() {
        TunnelRelay relay = new TunnelRelay();
        assertFalse(relay.isRunning());
    }

    @Test
    public void stop_ShouldSetRunningToFalse() {
        TunnelRelay relay = new TunnelRelay();
        relay.stop();
        assertFalse(relay.isRunning());
    }

    @Test
    public void getLastStopReason_ShouldBeNullInitially() {
        TunnelRelay relay = new TunnelRelay();
        assertNull(relay.getLastStopReason());
    }

    @Test
    public void start_WithNullContext_ShouldThrowException() {
        TunnelRelay relay = new TunnelRelay();
        try {
            relay.start(null, null, null);
            assertFalse("Should have thrown IOException", true);
        } catch (Exception e) {
            assertFalse(relay.isRunning());
        }
    }

    @Test
    public void start_WithNullPppSession_ShouldThrowException() {
        TunnelRelay relay = new TunnelRelay();
        try {
            relay.start(null, null, new PppSession());
            assertFalse("Should have thrown IOException", true);
        } catch (Exception e) {
            assertFalse(relay.isRunning());
        }
    }

    @Test
    public void multipleStops_ShouldNotThrow() {
        TunnelRelay relay = new TunnelRelay();
        relay.stop();
        relay.stop();
        relay.stop();
        assertFalse(relay.isRunning());
    }
}
