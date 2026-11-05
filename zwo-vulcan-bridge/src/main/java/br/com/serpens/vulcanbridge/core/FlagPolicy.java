package br.com.serpens.vulcanbridge.core;

import java.util.Locale;
import java.util.Set;

/**
 * Regras de avaliação de flags (o que conta como "flag alta e repetida").
 *
 * @param minVlRatio         uma flag é "alta" quando VL &gt;= maxVl * minVlRatio
 * @param minVl              piso absoluto de VL para uma flag ser "alta"
 * @param requiredHighFlags  quantas flags altas dentro da janela confirmam uma ocorrência
 * @param windowMillis       tamanho da janela de tempo das flags altas
 * @param onlyPunishable     ignora checks que o Vulcan não marca como puníveis
 * @param excludeBedrock     jogadores Bedrock nunca entram na escada de punições
 * @param ignoredChecks      checks ignorados: "nome" ou "nome:tipo", em minúsculas
 * @param ignoredCategories  categorias ignoradas, em minúsculas
 */
public record FlagPolicy(
        double minVlRatio,
        int minVl,
        int requiredHighFlags,
        long windowMillis,
        boolean onlyPunishable,
        boolean excludeBedrock,
        Set<String> ignoredChecks,
        Set<String> ignoredCategories) {

    public static final int MAX_REQUIRED_FLAGS = 64;

    public FlagPolicy {
        minVlRatio = Math.max(0.0, Math.min(1.0, minVlRatio));
        minVl = Math.max(1, minVl);
        requiredHighFlags = Math.max(1, Math.min(MAX_REQUIRED_FLAGS, requiredHighFlags));
        windowMillis = Math.max(1000L, windowMillis);
        ignoredChecks = Set.copyOf(ignoredChecks);
        ignoredCategories = Set.copyOf(ignoredCategories);
    }

    public static FlagPolicy defaults() {
        return new FlagPolicy(1.0, 3, 3, 60_000L, true, true, Set.of(), Set.of());
    }

    /** VL mínimo para a flag contar como alta. */
    public int highVlThreshold(int maxVl) {
        if (maxVl <= 0) {
            return minVl;
        }
        return Math.max(minVl, (int) Math.ceil(maxVl * minVlRatio));
    }

    public boolean isIgnored(FlagInfo flag) {
        if (onlyPunishable && !flag.punishable()) {
            return true;
        }
        if (flag.category() != null && ignoredCategories.contains(flag.category().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (ignoredChecks.isEmpty()) {
            return false;
        }
        String name = flag.checkName() == null ? "" : flag.checkName().toLowerCase(Locale.ROOT);
        return ignoredChecks.contains(name) || ignoredChecks.contains(flag.key());
    }
}
