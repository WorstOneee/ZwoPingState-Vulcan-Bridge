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

    private BridgeRegistration() {
    }

    public static void register(JavaPlugin plugin, PingTracker tracker) {
        plugin.getServer().getServicesManager()
                .register(PingStateProvider.class, new BridgeProvider(tracker), plugin, ServicePriority.Normal);
    }
}
