package br.com.serpens.vulcanbridge.core;

import java.util.Locale;
import java.util.Set;

/**
 * Garante que a ponte nunca execute um comando que bana por IP.
 *
 * <p>Atenção: o {@code /mpban} do ModPanel/SerpensModPanel também aplica ban de IP quando o IP
 * tem poucas contas conhecidas ({@code limits.ip-ban-account-limit}, mínimo 2). Por isso ele
 * também é recusado aqui. Use um comando que bana somente o perfil (UUID).</p>
 */
public final class BanCommandGuard {

    /** Comandos recusados (primeira palavra, sem "/", sem namespace). */
    public static final Set<String> REFUSED = Set.of(
            "ban-ip", "banip", "ipban", "mpipban", "tempbanip", "tempipban", "banip-temp",
            // ModPanel / SerpensModPanel: banem também o último IP conhecido
            "mpban", "mptempban");

    private BanCommandGuard() {
    }

    public record Verdict(boolean allowed, String reason) {
    }

    public static Verdict check(String command) {
        if (command == null || command.isBlank()) {
            return new Verdict(false, "comando de ban vazio");
        }
        String first = command.trim();
        if (first.startsWith("/")) {
            first = first.substring(1);
        }
        int space = first.indexOf(' ');
        if (space >= 0) {
            first = first.substring(0, space);
        }
        int colon = first.indexOf(':');
        if (colon >= 0) {
            first = first.substring(colon + 1);
        }
        first = first.toLowerCase(Locale.ROOT);
        if (REFUSED.contains(first)) {
            return new Verdict(false, "o comando '" + first + "' pode banir por IP e é recusado pela ponte");
        }
        if (!command.contains("{uuid}") && !command.contains("{player}")) {
            return new Verdict(false, "o comando de ban precisa conter {uuid} ou {player}");
        }
        return new Verdict(true, "ok");
    }
}
