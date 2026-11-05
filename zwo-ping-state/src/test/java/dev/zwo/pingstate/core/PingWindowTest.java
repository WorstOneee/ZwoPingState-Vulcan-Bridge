package dev.zwo.pingstate.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PingWindowTest {

    @Test
    void circularOverwriteKeepsMostRecent() {
        PingWindow w = new PingWindow(4);
        for (int v : new int[]{10, 20, 30, 40, 50, 60}) {
            w.add(v);
        }
        assertEquals(4, w.count());
        assertEquals(30, w.get(0));
        assertEquals(60, w.get(3));
        WindowStats s = new WindowStats();
        w.compute(80, 400, s);
        assertEquals(45, s.average);
        assertEquals(30, s.min);
        assertEquals(60, s.max);
        assertEquals(60, s.last);
        assertEquals(10, s.jitter);
        assertEquals(0, s.spikes);
    }

    @Test
    void spikesByJumpAndAbsolute() {
        PingWindow w = new PingWindow(10);
        for (int v : new int[]{40, 40, 140, 40, 450}) {
            w.add(v);
        }
        WindowStats s = new WindowStats();
        w.compute(80, 400, s);
        // 140 (salto +100), 40 (salto -100), 450 (absoluto e salto)
        assertEquals(3, s.spikes);
    }
}
