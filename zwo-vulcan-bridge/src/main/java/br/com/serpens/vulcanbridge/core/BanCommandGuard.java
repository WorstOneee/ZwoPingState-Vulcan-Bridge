package br.com.serpens.vulcanbridge.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reconhece comandos que banem por IP. A ponte usa isso para conferir os
 * {@code punishment-commands} do Vulcan na inicialização e avisar a staff: a política do
 * servidor é ban só por perfil.
 *
 * <p>Atenção: o {@code /mpban} e o {@code /mptempban} do ModPanel também banem o último IP
 * conhecido (quando o IP tem poucas contas). Para ban só por perfil use {@code /mpbanperfil}.</p>
 */
public final class BanCommandGuard {

    /** Primeira palavra (sem "/", sem namespace) de comandos que podem banir IP. */
    public static final Set<String> IP_BAN_COMMANDS = Set.of(
            "ban-ip", "banip", "ipban", "mpipban", "tempbanip", "tempipban", "banip-temp",
            // ModPanel / SerpensModPanel: banem também o último IP conhecido
            "mpban", "mptempban");

    private BanCommandGuard() {
    }

    /** true se o comando pode banir por IP. */
    public static boolean bansIp(String command) {
        String label = label(command);
        return label != null && IP_BAN_COMMANDS.contains(label);
    }

    /** Comandos (sem repetição, na ordem) que podem banir por IP. */
    public static List<String> ipBanCommands(Collection<String> commands) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : commands) {
            if (bansIp(c)) {
                out.add(c.trim());
            }
        }
        return new ArrayList<>(out);
    }

    static String label(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String first = command.trim();
        while (first.startsWith("/")) {
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
        return first.toLowerCase(Locale.ROOT);
    }
}
