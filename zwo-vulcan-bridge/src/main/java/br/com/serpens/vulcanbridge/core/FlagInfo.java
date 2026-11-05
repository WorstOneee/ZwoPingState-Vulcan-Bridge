package br.com.serpens.vulcanbridge.core;

import java.util.Locale;

/**
 * Dados de uma flag do Vulcan, já extraídos do evento.
 *
 * @param checkName  nome do check (ex.: "speed")
 * @param checkType  tipo do check (ex.: 'a')
 * @param category   categoria (combat, movement, player...)
 * @param vl         VL atual do check
 * @param maxVl      VL máximo configurado no Vulcan para o check
 * @param punishable se o check está marcado como punível no Vulcan
 */
public record FlagInfo(String checkName, char checkType, String category, int vl, int maxVl, boolean punishable) {

    /** Chave "nome:tipo" em minúsculas, usada na lista de checks ignorados. */
    public String key() {
        return (checkName == null ? "?" : checkName.toLowerCase(Locale.ROOT)) + ":" + Character.toLowerCase(checkType);
    }

    public String display() {
        return checkName + " (" + Character.toUpperCase(checkType) + ") VL " + vl + "/" + maxVl;
    }
}
