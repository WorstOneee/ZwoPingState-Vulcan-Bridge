package dev.zwo.pingstate.core;

/**
 * Estado pronto de um jogador, publicado pela coleta e lido pela consulta/comando.
 * Imutável; trocado inteiro (campo volátil) a cada avaliação.
 *
 * @param status                 estado + motivo calculados na última amostra
 * @param evaluatedAtNanos       quando foi calculado (System.nanoTime)
 * @param lastSampleNanos        quando foi lida a última amostra (0 = nunca)
 * @param recoveryRemainingNanos quanto falta para voltar a STABLE (0 se não está em recuperação)
 */
public record PingSnapshot(
        PingStatus status,
        long evaluatedAtNanos,
        long lastSampleNanos,
        int lastPing,
        int averagePing,
        int minPing,
        int maxPing,
        int jitter,
        int spikes,
        int samples,
        long recoveryRemainingNanos) {

    static PingSnapshot initial(long nowNanos) {
        return new PingSnapshot(PingStatus.of(PingState.UNKNOWN, PingReason.INSUFFICIENT_DATA),
                nowNanos, 0L, -1, -1, -1, -1, -1, 0, 0, 0L);
    }
}
