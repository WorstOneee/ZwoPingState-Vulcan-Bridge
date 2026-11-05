package br.com.serpens.vulcanbridge.core;

import java.util.Locale;

/** Modo de operação da ponte. Quem pune é sempre o Vulcan; a ponte só deixa passar ou bloqueia. */
public enum BridgeMode {
    /**
     * Padrão. Bloqueia TODAS as punições do Vulcan e só registra o que teria passado. Não cancela
     * flags (os alertas do Vulcan continuam normais).
     */
    OBSERVE,
    /**
     * O Vulcan pune normalmente quando a conexão está STABLE. Com lag, provedor ausente, Bedrock ou
     * proteção de teleporte/respawn, a punição é cancelada (e, se configurado, a flag também).
     */
    ENFORCE;

    public static BridgeMode parse(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ENFORCE", "FILTER", "FILTRAR", "PUNIR", "ATIVO" -> ENFORCE;
            default -> OBSERVE;
        };
    }
}
