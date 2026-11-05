package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
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
 */
final class ProviderTracker implements Listener {

    static final ConnectionStatus UNAVAILABLE =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.PROVIDER_UNAVAILABLE);
    static final ConnectionStatus FAILED =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.COLLECTION_ERROR);

    private record Current(PingStateProvider provider, Plugin owner) {
    }

    private volatile Current current;
    private final LongAdder failures = new LongAdder();

    void refresh() {
        refreshExcluding(null);
    }

    private void refreshExcluding(Object excluded) {
        Current next = null;
        for (RegisteredServiceProvider<PingStateProvider> rsp :
                Bukkit.getServicesManager().getRegistrations(PingStateProvider.class)) {
            if (rsp.getProvider() != excluded && rsp.getPlugin().isEnabled()) {
                next = new Current(rsp.getProvider(), rsp.getPlugin());
                break; // getRegistrations já vem ordenado por prioridade
            }
        }
        current = next;
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

    /** O(1). Nunca lança exceção. Provedor ausente ou desligado = UNKNOWN. */
    ConnectionStatus status(UUID playerId) {
        Current c = current;
        if (c == null || !c.owner().isEnabled()) {
            return UNAVAILABLE;
        }
        try {
            ConnectionStatus s = c.provider().status(playerId);
            return s == null ? FAILED : s;
        } catch (Throwable t) {
            failures.increment();
            return FAILED;
        }
    }

    String describe() {
        Current c = current;
        if (c == null) {
            return "nenhum provedor registrado";
        }
        return c.owner().getName() + " " + c.owner().getPluginMeta().getVersion()
                + (c.owner().isEnabled() ? "" : " (desligado)");
    }

    boolean available() {
        Current c = current;
        return c != null && c.owner().isEnabled();
    }

    long failures() {
        return failures.sum();
    }
}
