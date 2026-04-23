package com.map.sstp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.map.sstp.protocol.SstpControlChannel;
import com.map.sstp.ppp.PppSession;
import org.junit.Test;

public class SstpControlChannelTest {

    @Test
    public void initialState_ShouldBeNotStarted() {
        SstpControlChannel channel = new SstpControlChannel();

        assertFalse(channel.isStarted());
        assertFalse(channel.isConnectAckReceived());
        assertFalse(channel.isCallConnectedSent());
        assertNull(channel.getLastHeader());
        assertNull(channel.getCryptoBindingRequest());
        assertNull(channel.getCryptoBindingMaterial());
    }

    @Test
    public void stop_ShouldResetState() {
        SstpControlChannel channel = new SstpControlChannel();
        channel.stop();

        assertFalse(channel.isStarted());
        assertFalse(channel.isConnectAckReceived());
        assertFalse(channel.isCallConnectedSent());
        assertNull(channel.getLastHeader());
        assertNull(channel.getCryptoBindingRequest());
        assertNull(channel.getCryptoBindingMaterial());
    }

    @Test
    public void completeCallConnected_WithoutConnectAck_ShouldFail() throws Exception {
        SstpControlChannel channel = new SstpControlChannel();
        PppSession ppp = new PppSession();

        try {
            channel.completeCallConnected(null, ppp);
            assertTrue("Should have thrown IOException", false);
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains("Call Connect Ack"));
        }
    }
}
