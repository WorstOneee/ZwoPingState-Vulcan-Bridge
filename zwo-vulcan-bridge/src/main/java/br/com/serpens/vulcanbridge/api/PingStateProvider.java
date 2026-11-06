package br.com.serpens.vulcanbridge.api;

import java.util.UUID;

/**
 * Provedor da qualidade de conexão dos jogadores, registrado no Bukkit
 * {@code ServicesManager}.
 *
 * <p>Contrato para implementações:</p>
 * <ul>
 *   <li>O(1), somente memória: sem I/O, sem criar tarefas, sem percorrer janelas de amostras
 *       e sem chamar a API do Bukkit para buscar o jogador.</li>
 *   <li>Thread-safe: a ponte pode consultar a partir de threads que não são a principal
 *       (o Vulcan pode disparar eventos fora da thread do servidor).</li>
 *   <li>Nunca retorna {@code null} e nunca lança exceção; em caso de dúvida, {@link ConnectionState#UNKNOWN}.</li>
 * </ul>
 *
 * <p>Desde a ponte 3.1.0 a automação exige {@link #snapshotVersion()} &gt;= {@link #SNAPSHOT_VERSION}:
 * um provedor que não informa revisões bloqueia a automação.</p>
 */
public interface PingStateProvider {

    /** Versão do contrato de {@link #snapshot(UUID)} exigida pela ponte. */
    int SNAPSHOT_VERSION = 1;

    /** Estado atual da conexão do jogador. */
    ConnectionState connection(UUID playerId);

    /**
     * Estado + motivo. Implementações devem sobrescrever para informar o motivo real;
     * a versão padrão só repassa o estado.
     */
    default ConnectionStatus status(UUID playerId) {
        ConnectionState state = connection(playerId);
        return ConnectionStatus.of(state, state == ConnectionState.STABLE ? ConnectionReason.OK : ConnectionReason.UNSPECIFIED);
    }

    /**
     * Versão do snapshot com revisões que esta implementação fornece. 0 = não fornece
     * (provedores anteriores à API 3.1.0).
     */
    default int snapshotVersion() {
        return 0;
    }

    /**
     * Estado, sessão de coleta, revisões e frescor numa única leitura consistente: o estado STABLE
     * devolvido nunca vem acompanhado de uma revisão anterior à instabilidade que o precedeu.
     *
     * <p>A versão padrão (provedores antigos) devolve {@link ConnectionSnapshot#unsupported}, que
     * nunca permite avaliação.</p>
     */
    default ConnectionSnapshot snapshot(UUID playerId) {
        return ConnectionSnapshot.unsupported(status(playerId));
    }
}
