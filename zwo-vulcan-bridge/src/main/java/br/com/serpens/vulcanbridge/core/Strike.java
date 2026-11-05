package br.com.serpens.vulcanbridge.core;

import java.util.Locale;

/**
 * Uma ocorrência válida já tratada (kick confirmado, ban aplicado, ou o equivalente simulado
 * no modo observação).
 *
 * @param atMillis   quando aconteceu
 * @param sessionKey identificador da sessão (join) em que aconteceu
 * @param action     KICK ou BAN
 * @param mode       modo em que foi registrada; o modo ENFORCE nunca conta strikes do OBSERVE
 * @param check      check do Vulcan que confirmou a ocorrência
 */
public record Strike(long atMillis, String sessionKey, Action action, BridgeMode mode, String check) {

    public enum Action { KICK, BAN }

    /** Serialização simples em uma linha, separada por '|'. */
    public String serialize() {
        return atMillis + "|" + sessionKey + "|" + action + "|" + mode + "|" + check.replace('|', '/');
    }

    public static Strike parse(String line) {
        String[] p = line.split("\\|", 5);
        if (p.length < 5) {
            throw new IllegalArgumentException("linha de strike inválida: " + line);
        }
        return new Strike(Long.parseLong(p[0].trim()), p[1], Action.valueOf(p[2].trim().toUpperCase(Locale.ROOT)),
                BridgeMode.valueOf(p[3].trim().toUpperCase(Locale.ROOT)), p[4]);
    }
}
