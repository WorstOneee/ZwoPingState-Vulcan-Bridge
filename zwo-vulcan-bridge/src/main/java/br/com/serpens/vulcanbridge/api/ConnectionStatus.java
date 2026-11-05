package br.com.serpens.vulcanbridge.api;

import java.util.Objects;

/**
 * Par imutável estado + motivo. As instâncias são pré-criadas: {@link #of} não aloca,
 * então pode ser usado no caminho quente (uma consulta por flag do Vulcan).
 */
public record ConnectionStatus(ConnectionState state, ConnectionReason reason) {

    private static final ConnectionStatus[][] CACHE;

    static {
        ConnectionState[] states = ConnectionState.values();
        ConnectionReason[] reasons = ConnectionReason.values();
        CACHE = new ConnectionStatus[states.length][reasons.length];
        for (ConnectionState s : states) {
            for (ConnectionReason r : reasons) {
                CACHE[s.ordinal()][r.ordinal()] = new ConnectionStatus(s, r);
            }
        }
    }

    public ConnectionStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
    }

    /** Instância compartilhada (sem alocação). */
    public static ConnectionStatus of(ConnectionState state, ConnectionReason reason) {
        return CACHE[state.ordinal()][reason.ordinal()];
    }

    public boolean allowsEvaluation() {
        return state.allowsEvaluation();
    }
}
