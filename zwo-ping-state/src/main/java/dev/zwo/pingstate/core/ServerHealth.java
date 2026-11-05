package dev.zwo.pingstate.core;

/**
 * Saúde global do servidor, compartilhada por todos os jogadores.
 *
 * <p>Atualizada na thread principal: a cada tick a coleta mede o intervalo desde o tick anterior
 * e o fim do tick informa a duração (ambos podem marcar travada); uma vez por intervalo de
 * amostragem calcula TPS curto e MSPT médio. Nunca percorre jogadores. A consulta só lê campos
 * voláteis.</p>
 */
public final class ServerHealth {

    public enum Cause { NONE, STALL, LOW_TPS, HIGH_MSPT }

    private static final int GAP_WINDOW = 20;

    // --- só thread principal ---
    private final long[] gaps = new long[GAP_WINDOW];
    private int gapIndex;
    private int gapCount;
    private long gapSum;
    private long lastTickNanos;
    private boolean hasLastTick;
    private final double[] durations = new double[GAP_WINDOW];
    private int durIndex;
    private int durCount;
    private double durSum;

    // --- lidos por qualquer thread ---
    private volatile boolean everBad;
    private volatile long lastBadNanos;
    private volatile Cause lastCause = Cause.NONE;
    private volatile double lastCauseValue;
    private volatile boolean everStalled;
    private volatile long lastStallNanos;
    private volatile long lastStallLengthNanos;
    private volatile double shortTps = 20.0;
    private volatile double mspt;

    /** A cada tick: mede o intervalo e marca travada se passar do limite. O(1). */
    public void onTick(long nowNanos, PingSettings s) {
        if (hasLastTick) {
            long gap = nowNanos - lastTickNanos;
            gapSum += gap - gaps[gapIndex];
            gaps[gapIndex] = gap;
            gapIndex = (gapIndex + 1) % GAP_WINDOW;
            if (gapCount < GAP_WINDOW) {
                gapCount++;
            }
            if (gap > s.stallNanos()) {
                lastStallNanos = nowNanos;
                lastStallLengthNanos = gap;
                everStalled = true;
                markBad(nowNanos, Cause.STALL, gap / 1_000_000.0);
            }
        }
        lastTickNanos = nowNanos;
        hasLastTick = true;
    }

    /**
     * Fim de cada tick (ServerTickEndEvent do Paper): só soma a duração numa janela fixa e, se o
     * tick passou do limite de travada, atualiza o marcador global. O(1), sem percorrer jogadores.
     * Substitui o Server#getAverageTickTime(), que no Paper 1.21.11 recalcula estatísticas da janela
     * de ticks a cada chamada.
     */
    public void onTickEnd(long nowNanos, double durationMs, PingSettings s) {
        if (!(durationMs >= 0)) {
            return;
        }
        durSum += durationMs - durations[durIndex];
        durations[durIndex] = durationMs;
        durIndex = (durIndex + 1) % GAP_WINDOW;
        if (durCount < GAP_WINDOW) {
            durCount++;
        }
        if (durationMs * 1_000_000L > s.stallNanos()) {
            lastStallNanos = nowNanos;
            lastStallLengthNanos = (long) (durationMs * 1_000_000L);
            everStalled = true;
            markBad(nowNanos, Cause.STALL, durationMs);
        }
    }

    /** Uma vez por intervalo: TPS curto e MSPT médio, ambos dos últimos 20 ticks. */
    public void refresh(long nowNanos, PingSettings s) {
        double tps = 20.0;
        if (gapCount > 0 && gapSum > 0) {
            tps = Math.min(20.0, gapCount * 1_000_000_000.0 / gapSum);
        }
        shortTps = tps;
        if (durCount > 0) {
            mspt = durSum / durCount;
        }
        if (tps < s.minTps()) {
            markBad(nowNanos, Cause.LOW_TPS, tps);
        }
        if (durCount > 0 && mspt > s.maxMspt()) {
            markBad(nowNanos, Cause.HIGH_MSPT, mspt);
        }
    }

    private void markBad(long nowNanos, Cause cause, double value) {
        lastCause = cause;
        lastCauseValue = value;
        lastBadNanos = nowNanos;
        everBad = true;
    }

    /** O(1). Instável enquanto não passar o período de recuperação desde o último sinal ruim. */
    public boolean unstable(long nowNanos, PingSettings s) {
        return everBad && nowNanos - lastBadNanos < s.serverRecoveryNanos();
    }

    public double shortTps() { return shortTps; }
    public double mspt() { return mspt; }
    public Cause lastCause() { return lastCause; }
    public double lastCauseValue() { return lastCauseValue; }
    public boolean everBad() { return everBad; }
    public long lastBadNanos() { return lastBadNanos; }
    public boolean everStalled() { return everStalled; }
    public long lastStallNanos() { return lastStallNanos; }
    public long lastStallLengthNanos() { return lastStallLengthNanos; }

    /** Reinicia a medição de intervalos (ex.: depois de recarregar), sem apagar o histórico. */
    public void resetTickClock() {
        hasLastTick = false;
    }
}
