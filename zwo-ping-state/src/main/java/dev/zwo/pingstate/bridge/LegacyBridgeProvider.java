package dev.zwo.pingstate.bridge;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.api.PingStateProvider;
import dev.zwo.pingstate.core.PingStatus;
import dev.zwo.pingstate.core.PingTracker;

import java.util.UUID;

/**
 * Provedor só de estado, para pontes anteriores à 3.1.0 (sem {@code ConnectionSnapshot}). Não
 * referencia tipos novos da API, então carrega em pontes antigas.
 */
final class LegacyBridgeProvider implements PingStateProvider {

    private final PingTracker tracker;

    LegacyBridgeProvider(PingTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public ConnectionState connection(UUID playerId) {
        return status(playerId).state();
    }

    @Override
    public ConnectionStatus status(UUID playerId) {
        if (playerId == null) {
            return ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.COLLECTION_ERROR);
        }
        try {
            PingStatus s = tracker.query(playerId, System.nanoTime());
            ConnectionReason r;
            try {
                r = ConnectionReason.valueOf(s.reason().name());
            } catch (IllegalArgumentException e) {
                r = ConnectionReason.UNSPECIFIED;
            }
            return ConnectionStatus.of(ConnectionState.valueOf(s.state().name()), r);
        } catch (RuntimeException e) {
            return ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.COLLECTION_ERROR);
        }
    }
}
