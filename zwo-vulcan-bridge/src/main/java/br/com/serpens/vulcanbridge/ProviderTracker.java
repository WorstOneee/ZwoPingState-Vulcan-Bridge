package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionSnapshot;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.api.PingStateProvider;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mantém em cache o {@link PingStateProvider} registrado no ServicesManager.
 * A consulta não acessa o ServicesManager: só lê um campo volátil.
 *
 * <p>Cada troca de provedor (registro, remoção, reinício) avança a {@link #generation() geração}:
 * evidências ancoradas em outra geração deixam de valer.</p>
 */
final class ProviderTracker implements Listener {

    static final ConnectionStatus UNAVAILABLE =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.PROVIDER_UNAVAILABLE);
    static final ConnectionStatus FAILED =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.COLLECTION_ERROR);

    private static final ConnectionSnapshot SNAP_UNAVAILABLE =
            ConnectionSnapshot.unavailable(ConnectionReason.PROVIDER_UNAVAILABLE);
    private static final ConnectionSnapshot SNAP_INCOMPATIBLE = ConnectionSnapshot.unsupported(
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.PROVIDER_INCOMPATIBLE));
    /** Falha ao consultar: nunca utilizável (sem sessão, vencido) → descarta. */
    private static final ConnectionSnapshot SNAP_FAILED =
            ConnectionSnapshot.unavailable(ConnectionReason.COLLECTION_ERROR);

    private record Current(PingStateProvider provider, Plugin owner, int snapshotVersion, long generation) {
        boolean compatible() {
            return snapshotVersion >= PingStateProvider.SNAPSHOT_VERSION;
        }
    }

    private volatile Current current;
    private volatile long generation;
    private final LongAdder failures = new LongAdder();
    private final LongAdder invalidSnapshots = new LongAdder();

    void refresh() {
        refreshExcluding(null);
    }

    private synchronized void refreshExcluding(Object excluded) {
        Current prev = current;
        RegisteredServiceProvider<PingStateProvider> found = null;
        for (RegisteredServiceProvider<PingStateProvider> rsp :
                Bukkit.getServicesManager().getRegistrations(PingStateProvider.class)) {
            if (rsp.getProvider() != excluded && rsp.getPlugin().isEnabled()) {
                found = rsp;
                break; // getRegistrations já vem ordenado por prioridade
            }
        }
        if (found == null) {
            if (prev != null) {
                generation++;
            }
            current = null;
            return;
        }
        if (prev != null && prev.provider() == found.getProvider() && prev.owner() == found.getPlugin()) {
            return; // mesmo provedor: mantém a geração
        }
        int version;
        try {
            version = found.getProvider().snapshotVersion();
        } catch (Throwable t) {
            version = 0; // provedor compilado contra a API antiga sem o método
        }
        long gen = ++generation;
        current = new Current(found.getProvider(), found.getPlugin(), version, gen);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == PingStateProvider.class) {
            refresh();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == PingStateProvider.class) {
            refreshExcluding(event.getProvider().getProvider());
        }
    }

    /** Geração atual (muda a cada troca/remoção de provedor). */
    long generation() {
        Current c = current;
        return c == null ? -1L : c.generation();
    }

    /**
     * O(1), memória. Uma consulta = um snapshot. Nunca lança exceção nem devolve {@code null}.
     * Provedor ausente/desligado/antigo devolve um snapshot que nunca permite avaliação.
     */
    ConnectionSnapshot snapshot(UUID playerId) {
        Current c = current;
        if (c == null || !c.owner().isEnabled()) {
            return SNAP_UNAVAILABLE;
        }
        if (!c.compatible()) {
            return SNAP_INCOMPATIBLE;
        }
        try {
            ConnectionSnapshot s = c.provider().snapshot(playerId);
            if (s == null) {
                invalidSnapshots.increment();
                return SNAP_FAILED;
            }
            if (s.revisionsSupported() && s.providerEpoch() == 0L) {
                // Diz ter revisões mas não informa a identidade: não dá para confiar.
                invalidSnapshots.increment();
                return SNAP_INCOMPATIBLE;
            }
            return s;
        } catch (Throwable t) {
            failures.increment();
            return SNAP_FAILED;
        }
    }

    /** Só para diagnóstico. */
    ConnectionStatus status(UUID playerId) {
        return snapshot(playerId).status();
    }

    String describe() {
        Current c = current;
        if (c == null) {
            return "nenhum provedor registrado";
        }
        return c.owner().getName() + " " + c.owner().getPluginMeta().getVersion()
                + (c.owner().isEnabled() ? "" : " (desligado)")
                + (c.compatible() ? " (snapshot v" + c.snapshotVersion() + ")"
                : " — INCOMPATÍVEL: sem snapshot com revisões (atualize o ZwoPingState para 1.1.0+)");
    }

    boolean available() {
        Current c = current;
        return c != null && c.owner().isEnabled();
    }

    /** Disponível e com snapshot com revisões. */
    boolean compatible() {
        Current c = current;
        return c != null && c.owner().isEnabled() && c.compatible();
    }

    long failures() {
        return failures.sum();
    }

    long invalidSnapshots() {
        return invalidSnapshots.sum();
    }
}
