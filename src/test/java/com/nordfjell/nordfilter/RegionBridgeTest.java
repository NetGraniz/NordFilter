package com.nordfjell.nordfilter;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

final class RegionBridgeTest {
    @Test void regionWaitingAndCancelledTasksRetainTheirCapacitySlot() {
        var bridge=new MainThreadBridge<Integer,Integer>(1);
        var queued=bridge.offer(1,100);
        var scheduled=new ArrayList<MainThreadBridge.Ticket<Integer,Integer>>();
        bridge.drainAsync(1,1,scheduled::add);
        assertEquals(1,scheduled.size());assertEquals(1,bridge.size());
        assertNull(bridge.offer(2,100));
        queued.cancel();
        assertNull(bridge.offer(3,100)); // Still occupies a scheduled entity callback.
        queued.release();queued.release(); // Retirement/finally is idempotent.
        assertEquals(0,bridge.size());
        var replacement=bridge.offer(4,100);assertNotNull(replacement);
        bridge.drainAsync(1,1,ticket -> ticket.complete(ticket.payload));
        assertEquals(4,replacement.result.join());assertEquals(0,bridge.size());
    }
    @Test void shutdownCancelsAlreadyScheduledRegionWork() {
        var bridge=new MainThreadBridge<Integer,Integer>(1);
        var queued=bridge.offer(1,100);
        bridge.drainAsync(1,1,ticket -> {});
        bridge.close();
        assertTrue(queued.result.isCancelled());assertEquals(0,bridge.size());
        assertNull(bridge.offer(2,100));queued.release();
    }
}
