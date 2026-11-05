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
 */
public interface PingStateProvider {

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
}
