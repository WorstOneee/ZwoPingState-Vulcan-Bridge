package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.AccountKind;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Decide no join se a conta é Java, Bedrock ou incerta. Nunca por flag.
 *
 * <p>Java só é confirmado pela API do Floodgate. Sinais de Bedrock (API, UUID no formato do
 * Floodgate, prefixo de nome) sempre excluem da automação; a ausência do prefixo nunca prova que a
 * conta é Java.</p>
 */
final class BedrockDetector {

    record Result(AccountKind kind, String detail) {
    }

    private final MethodHandle isFloodgatePlayer; // (UUID)boolean, já ligado à instância; pode ser null
    private final String floodgateState;

    private BedrockDetector(MethodHandle isFloodgatePlayer, String floodgateState) {
        this.isFloodgatePlayer = isFloodgatePlayer;
        this.floodgateState = floodgateState;
    }

    static BedrockDetector create(Logger logger) {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return new BedrockDetector(null, "ausente");
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                    floodgate.getClass().getClassLoader());
            Object api = apiClass.getMethod("getInstance").invoke(null);
            MethodHandle mh = MethodHandles.publicLookup()
                    .findVirtual(apiClass, "isFloodgatePlayer", MethodType.methodType(boolean.class, UUID.class))
                    .bindTo(api);
            logger.info("Floodgate detectado: contas Java/Bedrock serão confirmadas pela API.");
            return new BedrockDetector(mh, "conectado (" + floodgate.getPluginMeta().getVersion() + ")");
        } catch (Throwable t) {
            logger.warning("Floodgate presente, mas a API não pôde ser usada (" + t + "). "
                    + "Nenhuma conta será confirmada como Java: ocorrências só serão registradas para revisão.");
            return new BedrockDetector(null, "API indisponível (" + t.getClass().getSimpleName() + ")");
        }
    }

    Result classify(UUID id, String name, List<String> prefixes, boolean assumeJavaWithoutFloodgate) {
        boolean apiAnswered = false;
        if (isFloodgatePlayer != null) {
            try {
                if ((boolean) isFloodgatePlayer.invokeExact(id)) {
                    return new Result(AccountKind.BEDROCK, "Floodgate: jogador Bedrock");
                }
                apiAnswered = true;
            } catch (Throwable t) {
                return new Result(AccountKind.UNCERTAIN, "falha na API do Floodgate: " + t.getClass().getSimpleName());
            }
        }
        if (id.getMostSignificantBits() == 0L) {
            return new Result(AccountKind.BEDROCK, "UUID no formato do Floodgate");
        }
        if (name != null) {
            for (String p : prefixes) {
                if (p != null && !p.isEmpty() && name.startsWith(p)) {
                    return new Result(AccountKind.BEDROCK, "prefixo de nome '" + p + "'");
                }
            }
        }
        if (apiAnswered) {
            return new Result(AccountKind.JAVA, "Floodgate: não é Bedrock");
        }
        if (assumeJavaWithoutFloodgate) {
            return new Result(AccountKind.JAVA, "Floodgate " + floodgateState
                    + "; bedrock.assume-java-without-floodgate = true");
        }
        return new Result(AccountKind.UNCERTAIN, "Floodgate " + floodgateState + ": não dá para confirmar Java");
    }

    String floodgateState() {
        return floodgateState;
    }
}
