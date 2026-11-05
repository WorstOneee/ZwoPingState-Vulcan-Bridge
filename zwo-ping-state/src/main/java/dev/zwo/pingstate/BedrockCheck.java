package dev.zwo.pingstate;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.UUID;

/**
 * Identifica jogadores Bedrock (só no join). Para eles o Paper enxerga o ping do Geyser,
 * não o do cliente, então o estado informado é UNKNOWN (se configurado).
 */
final class BedrockCheck {

    private final MethodHandle isFloodgatePlayer;

    private BedrockCheck(MethodHandle mh) {
        this.isFloodgatePlayer = mh;
    }

    static BedrockCheck create() {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return new BedrockCheck(null);
        }
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                    floodgate.getClass().getClassLoader());
            Object instance = api.getMethod("getInstance").invoke(null);
            return new BedrockCheck(MethodHandles.publicLookup()
                    .findVirtual(api, "isFloodgatePlayer", MethodType.methodType(boolean.class, UUID.class))
                    .bindTo(instance));
        } catch (Throwable t) {
            return new BedrockCheck(null);
        }
    }

    boolean available() {
        return isFloodgatePlayer != null;
    }

    boolean isBedrock(UUID id, String name, List<String> prefixes) {
        if (isFloodgatePlayer != null) {
            try {
                if ((boolean) isFloodgatePlayer.invokeExact(id)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // heurísticas abaixo
            }
        }
        if (id.getMostSignificantBits() == 0L) {
            return true;
        }
        for (String p : prefixes) {
            if (p != null && !p.isEmpty() && name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
