package dev.zwo.pingstate.core;

/**
 * Custo da própria coleta, para diagnóstico e comparação com o Spark.
 * Escrito só pela coleta (thread principal); o comando lê na thread principal.
 */
public final class CollectorStats {

    private static final int MINUTE_TICKS = 1200;

    private long ticks;
    private long totalNanos;
    private long totalSamples;
    private double emaNanos;

    private long minuteTicks;
    private long minuteNanos;
    private long minuteMaxNanos;
    private long minuteSamples;

    private long lastMinuteAvgNanos;
    private long lastMinuteMaxNanos;
    private long lastMinuteSamples;
    private boolean hasLastMinute;

    private long budgetHits;
    private long errors;

    void record(long nanos, int samples) {
        ticks++;
        totalNanos += nanos;
        totalSamples += samples;
        emaNanos = ticks == 1 ? nanos : emaNanos * 0.98 + nanos * 0.02;

        minuteTicks++;
        minuteNanos += nanos;
        minuteSamples += samples;
        if (nanos > minuteMaxNanos) {
            minuteMaxNanos = nanos;
        }
        if (minuteTicks >= MINUTE_TICKS) {
            lastMinuteAvgNanos = minuteNanos / minuteTicks;
            lastMinuteMaxNanos = minuteMaxNanos;
            lastMinuteSamples = minuteSamples;
            hasLastMinute = true;
            minuteTicks = 0;
            minuteNanos = 0;
            minuteMaxNanos = 0;
            minuteSamples = 0;
        }
    }

    void budgetHit() { budgetHits++; }

    void error() { errors++; }

    public long ticks() { return ticks; }
    public long totalSamples() { return totalSamples; }
    public double averageNanosPerTick() { return ticks == 0 ? 0 : (double) totalNanos / ticks; }
    public double emaNanosPerTick() { return emaNanos; }
    public long budgetHits() { return budgetHits; }
    public long errors() { return errors; }

    /** Média por tick no último minuto completo (ou no minuto corrente, se ainda não houver). */
    public long minuteAverageNanos() {
        return hasLastMinute ? lastMinuteAvgNanos : (minuteTicks == 0 ? 0 : minuteNanos / minuteTicks);
    }

    public long minuteMaxNanos() {
        return hasLastMinute ? Math.max(lastMinuteMaxNanos, minuteMaxNanos) : minuteMaxNanos;
    }

    /** Amostras por segundo no último minuto completo (ou parcial). */
    public double samplesPerSecond() {
        if (hasLastMinute) {
            return lastMinuteSamples / 60.0;
        }
        return minuteTicks == 0 ? 0 : minuteSamples * 20.0 / minuteTicks;
    }
}
