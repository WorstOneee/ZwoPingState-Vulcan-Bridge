package br.com.serpens.vulcanbridge.core;

import java.util.Locale;

/** Modo de operação da ponte. */
public enum BridgeMode {
    /**
     * Padrão. A ponte junta as evidências e registra o que teria feito, mas não kicka, não bane e
     * não altera o histórico real de ocorrências.
     */
    OBSERVE,
    /** A ponte aplica a sequência (kick, kick, ban temporário pelo ModPanel) por UUID. */
    ENFORCE;

    public static BridgeMode parse(String raw) {
        if (raw == null) {
            return OBSERVE;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ENFORCE", "PUNIR", "ATIVO" -> ENFORCE;
            default -> OBSERVE;
        };
    }
}
