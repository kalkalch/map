package com.map.sstp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SstpRoutePlannerTest {

    @Test
    public void plan_ShouldUseOnlyConfiguredRemoteProxy() throws Exception {
        SstpRoutePlanner.Plan plan = SstpRoutePlanner.plan("127.0.0.1", 1081);

        assertTrue(plan.isValid());
        assertEquals("127.0.0.1", plan.getRouteAddress());
        assertEquals("127.0.0.1", plan.getRemoteProxy().getHost());
        assertEquals(1081, plan.getRemoteProxy().getPort());
    }
}
