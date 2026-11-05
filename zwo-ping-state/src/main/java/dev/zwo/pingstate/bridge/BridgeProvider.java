package dev.zwo.pingstate.bridge;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.api.PingStateProvider;
import dev.zwo.pingstate.core.PingReason;
import dev.zwo.pingstate.core.PingState;
import dev.zwo.pingstate.core.PingStatus;
import dev.zwo.pingstate.core.PingTracker;

import java.util.UUID;

/**
 * Implementação de {@link PingStateProvider} da ZwoVulcanBridge.
 *
 * <p>A interface vem do JAR da ponte em tempo de execução (não é embutida). Esta classe só é
 * carregada se a ponte estiver instalada. O mapeamento de enums é feito uma única vez.</p>
 */
public final class BridgeProvider implements PingStateProvider {

    private static final ConnectionState[] STATES = new ConnectionState[PingState.values().length];
    private static final ConnectionStatus[][] STATUSES =
            new ConnectionStatus[PingState.values().length][PingReason.values().length];

    static {
        for (PingState s : PingState.values()) {
            ConnectionState cs = ConnectionState.valueOf(s.name());
            STATES[s.ordinal()] = cs;
            for (PingReason r : PingReason.values()) {
                ConnectionReason cr;
                try {
                    cr = ConnectionReason.valueOf(r.name());
                } catch (IllegalArgumentException e) {
                    cr = ConnectionReason.UNSPECIFIED;
                }
                STATUSES[s.ordinal()][r.ordinal()] = ConnectionStatus.of(cs, cr);
            }
        }
    }

    private static final ConnectionStatus FAILED =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.COLLECTION_ERROR);

    private final PingTracker tracker;

    public BridgeProvider(PingTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public ConnectionState connection(UUID playerId) {
        return status(playerId).state();
    }

    @Override
    public ConnectionStatus status(UUID playerId) {
        if (playerId == null) {
            return FAILED;
        }
        try {
            PingStatus s = tracker.query(playerId, System.nanoTime());
            return STATUSES[s.state().ordinal()][s.reason().ordinal()];
        } catch (RuntimeException e) {
            return FAILED;
        }
    }
}
