package br.com.serpens.vulcanbridge.core;

import java.util.Locale;

/** Modo de operação da ponte. */
public enum BridgeMode {
    /** Padrão. Avalia, registra e avisa a staff, mas não kicka nem bane ninguém. */
    OBSERVE,
    /** Executa a escada de punições (kick, kick, ban por perfil). */
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
