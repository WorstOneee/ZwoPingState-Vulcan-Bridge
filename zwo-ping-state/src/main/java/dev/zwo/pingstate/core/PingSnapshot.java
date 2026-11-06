package dev.zwo.pingstate.core;

/**
 * Estado pronto de um jogador, publicado pela coleta e lido pela consulta/comando.
 * Imutável; trocado inteiro (campo volátil) a cada avaliação.
 *
 * @param status                 estado + motivo calculados na última amostra
 * @param evaluatedAtNanos       quando foi calculado (System.nanoTime)
 * @param lastSampleNanos        quando foi lida a última amostra (0 = nunca)
 * @param recoveryRemainingNanos quanto falta para voltar a STABLE (0 se não está em recuperação)
 * @param revision               revisão de instabilidade do jogador nesta sessão de coleta. Avança a
 *                               cada avaliação não STABLE, falha, descontinuidade ou dado vencido;
 *                               nunca volta. Publicada junto com o estado (mesmo objeto).
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
        long recoveryRemainingNanos,
        long revision) {

    static PingSnapshot initial(long nowNanos, long revision) {
        return new PingSnapshot(PingStatus.of(PingState.UNKNOWN, PingReason.INSUFFICIENT_DATA),
                nowNanos, 0L, -1, -1, -1, -1, -1, 0, 0, 0L, revision);
    }
}
