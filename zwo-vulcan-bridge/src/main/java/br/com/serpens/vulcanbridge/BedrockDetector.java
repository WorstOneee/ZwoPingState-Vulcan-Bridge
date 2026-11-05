package br.com.serpens.vulcanbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Detecta jogadores Bedrock (Geyser/Floodgate). Executado só no join, nunca por flag.
 *
 * <p>Ordem: API do Floodgate (se instalada) -&gt; formato de UUID do Floodgate
 * (bits mais significativos = 0) -&gt; prefixo do nome configurado.</p>
 */
final class BedrockDetector {

    private final MethodHandle isFloodgatePlayer; // (UUID)boolean, já ligado à instância; pode ser null

    private BedrockDetector(MethodHandle isFloodgatePlayer) {
        this.isFloodgatePlayer = isFloodgatePlayer;
    }

    static BedrockDetector create(Logger logger) {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return new BedrockDetector(null);
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                    floodgate.getClass().getClassLoader());
            Object api = apiClass.getMethod("getInstance").invoke(null);
            MethodHandle mh = MethodHandles.publicLookup()
                    .findVirtual(apiClass, "isFloodgatePlayer", MethodType.methodType(boolean.class, UUID.class))
                    .bindTo(api);
            logger.info("Floodgate detectado: jogadores Bedrock serão identificados pela API.");
            return new BedrockDetector(mh);
        } catch (Throwable t) {
            logger.warning("Floodgate presente, mas a API não pôde ser usada (" + t + "). "
                    + "Usando apenas o formato de UUID e os prefixos de nome.");
            return new BedrockDetector(null);
        }
    }

    boolean isBedrock(UUID id, String name, List<String> prefixes) {
        if (isFloodgatePlayer != null) {
            try {
                if ((boolean) isFloodgatePlayer.invokeExact(id)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // cai para as heurísticas abaixo
            }
        }
        if (id.getMostSignificantBits() == 0L) {
            return true; // formato de UUID gerado pelo Floodgate
        }
        if (name != null) {
            for (String p : prefixes) {
                if (p != null && !p.isEmpty() && name.startsWith(p)) {
                    return true;
                }
            }
        }
        return false;
    }
}
