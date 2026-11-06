package br.com.serpens.vulcanbridge.api;

import java.util.Objects;

/**
 * Retrato imutável e consistente da conexão de um jogador, devolvido por
 * {@link PingStateProvider#snapshot(java.util.UUID)} numa única leitura.
 *
 * <p>As revisões só crescem. O provedor avança {@link #playerRevision()} sempre que a coleta do
 * jogador detecta instabilidade, perda de continuidade das amostras ou dados inconclusivos, e
 * {@link #serverRevision()} sempre que detecta instabilidade do servidor (vale para todos). Uma
 * recuperação nunca devolve a revisão ao valor anterior.</p>
 *
 * <p>{@link #providerEpoch()} muda a cada criação/reinício do provedor e {@link #sessionId()} a cada
 * entrada do jogador na coleta; juntos impedem confundir contadores novos com antigos.</p>
 *
 * <p>Quem consome deve tratar a sequência de evidências como válida só enquanto epoch, sessão e
 * as duas revisões forem exatamente as mesmas do início da sequência.</p>
 *
 * @param status             estado + motivo no momento da consulta
 * @param providerEpoch      identidade da instância do provedor (0 = não informado)
 * @param sessionId          identidade da sessão de coleta do jogador (0 = não monitorado)
 * @param playerRevision     revisão de instabilidade do jogador (monotônica)
 * @param serverRevision     revisão de instabilidade global do servidor (monotônica)
 * @param telemetryAgeMillis idade da última avaliação do jogador (-1 = sem dado)
 * @param fresh              {@code true} se a telemetria está dentro do prazo de validade
 * @param revisionsSupported {@code false} quando o provedor não informa revisões (provedor antigo):
 *                           nesse caso a ausência de revisão NÃO significa "sem lag"
 */
public record ConnectionSnapshot(
        ConnectionStatus status,
        long providerEpoch,
        long sessionId,
        long playerRevision,
        long serverRevision,
        long telemetryAgeMillis,
        boolean fresh,
        boolean revisionsSupported) {

    public ConnectionSnapshot {
        Objects.requireNonNull(status, "status");
    }

    /** Snapshot de um provedor que não informa revisões. Nunca permite avaliação. */
    public static ConnectionSnapshot unsupported(ConnectionStatus status) {
        return new ConnectionSnapshot(status == null
                ? ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.UNSPECIFIED) : status,
                0L, 0L, 0L, 0L, -1L, false, false);
    }

    /**
     * Sem dado (provedor ausente, jogador não monitorado, falha). Nunca utilizável: sem sessão de
     * coleta e vencido. Não é "incompatível": só descarta.
     */
    public static ConnectionSnapshot unavailable(ConnectionReason reason) {
        return new ConnectionSnapshot(ConnectionStatus.of(ConnectionState.UNKNOWN, reason),
                0L, 0L, 0L, 0L, -1L, false, true);
    }

    /** Só STABLE, com telemetria válida, de um provedor com revisões e com sessão de coleta. */
    public boolean usable() {
        return revisionsSupported && fresh && sessionId != 0L && status.allowsEvaluation();
    }

    public ConnectionState state() {
        return status.state();
    }
}
