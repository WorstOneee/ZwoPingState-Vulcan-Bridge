package dev.zwo.pingstate.core;

/** Resultado mutável de {@link PingWindow#compute}; reutilizado pelo coletor (sem alocação). */
public final class WindowStats {
    public int samples;
    public int last;
    public int min;
    public int max;
    public int average;
    public int jitter;
    public int spikes;

    void reset() {
        samples = 0;
        last = 0;
        min = 0;
        max = 0;
        average = 0;
        jitter = 0;
        spikes = 0;
    }
}
