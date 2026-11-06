package dev.zwo.pingstate.bridge;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionSnapshot;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.api.PingStateProvider;
import dev.zwo.pingstate.core.PingReason;
import dev.zwo.pingstate.core.PingState;
import dev.zwo.pingstate.core.PingStatus;
import dev.zwo.pingstate.core.PingTracker;

import java.util.UUID;
import java.util.function.LongSupplier;

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

    private static final ConnectionSnapshot NULL_ID = new ConnectionSnapshot(FAILED, 0L, 0L, 0L, 0L, -1L, false, true);

    private final PingTracker tracker;
    private final LongSupplier nanoClock;
    private final PingTracker.ViewFactory<ConnectionSnapshot> factory;

    public BridgeProvider(PingTracker tracker) {
        this(tracker, System::nanoTime);
    }

    /** Relógio injetável só para testes. */
    BridgeProvider(PingTracker tracker, LongSupplier nanoClock) {
        this.tracker = tracker;
        this.nanoClock = nanoClock;
        this.factory = (st, epoch, session, playerRev, serverRev, ageNanos, fresh) -> new ConnectionSnapshot(
                STATUSES[st.state().ordinal()][st.reason().ordinal()], epoch, session, playerRev, serverRev,
                ageNanos < 0 ? -1L : ageNanos / 1_000_000L, fresh, true);
    }

    @Override
    public int snapshotVersion() {
        return SNAPSHOT_VERSION;
    }

    /** O(1), memória. Uma leitura consistente; em erro devolve UNKNOWN sem sessão (nunca avalia). */
    @Override
    public ConnectionSnapshot snapshot(UUID playerId) {
        if (playerId == null) {
            return NULL_ID;
        }
        try {
            return tracker.view(playerId, nanoClock.getAsLong(), factory);
        } catch (RuntimeException e) {
            return new ConnectionSnapshot(FAILED, tracker.epoch(), 0L, 0L, 0L, -1L, false, true);
        }
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
            PingStatus s = tracker.query(playerId, nanoClock.getAsLong());
            return STATUSES[s.state().ordinal()][s.reason().ordinal()];
        } catch (RuntimeException e) {
            return FAILED;
        }
    }
}
