package dev.zwo.pingstate.bridge;

import br.com.serpens.vulcanbridge.api.PingStateProvider;
import dev.zwo.pingstate.core.PingTracker;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registro no ServicesManager. Isolado nesta classe para que o plugin principal não referencie
 * tipos da ponte: se a ponte não estiver instalada, esta classe nunca é carregada.
 */
public final class BridgeRegistration {

    static final String SNAPSHOT_CLASS = "br.com.serpens.vulcanbridge.api.ConnectionSnapshot";

    private BridgeRegistration() {
    }

    /** @return {@code true} se registrou o provedor completo (com snapshot e revisões) */
    public static boolean register(JavaPlugin plugin, PingTracker tracker) {
        boolean full = snapshotApiPresent();
        PingStateProvider provider = full ? new BridgeProvider(tracker) : new LegacyBridgeProvider(tracker);
        plugin.getServer().getServicesManager()
                .register(PingStateProvider.class, provider, plugin, ServicePriority.Normal);
        return full;
    }

    static boolean snapshotApiPresent() {
        try {
            Class.forName(SNAPSHOT_CLASS, false, PingStateProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
