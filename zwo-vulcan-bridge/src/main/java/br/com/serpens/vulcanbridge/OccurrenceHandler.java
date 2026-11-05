package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.FlagInfo;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import br.com.serpens.vulcanbridge.core.Strike;
import br.com.serpens.vulcanbridge.core.StrikeLedger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Executa a escada de punições quando o {@code FlagEngine} confirma uma ocorrência.
 * Ocorrências são raras; aqui pode haver log, alerta à staff e gravação (assíncrona).
 *
 * <p>Política: kick, kick (sessões distintas) e só na terceira ocorrência válida ban por perfil.
 * Nunca ban por IP. No modo OBSERVE nada é executado; só registrado e avisado.</p>
 */
final class OccurrenceHandler {

    static final String ALERT_PERMISSION = "zwovulcanbridge.alerts";

    private final ZwoVulcanBridgePlugin plugin;

    OccurrenceHandler(ZwoVulcanBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Pode ser chamado de qualquer thread. A execução vai para a thread principal. */
    void onOccurrence(PlayerSession session, FlagInfo flag, ConnectionStatus connection) {
        if (Bukkit.isPrimaryThread()) {
            handle(session, flag, connection);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> handle(session, flag, connection));
        }
    }

    private void handle(PlayerSession session, FlagInfo flag, ConnectionStatus connection) {
        if (!plugin.isEnabled()) {
            return;
        }
        BridgeSettings s = plugin.settings();
        StrikeLedger ledger = plugin.ledger();
        BridgeMode mode = s.mode();
        UUID id = session.playerId();
        long now = System.currentTimeMillis();
        StrikeLedger.Decision d = ledger.decide(id, mode, session.sessionKey(), now);
        String check = flag.display();
        String base = session.name() + " (" + id + ") — " + check + " — conexão " + connection.state()
                + "/" + connection.reason().name();

        if (mode == BridgeMode.OBSERVE) {
            Strike.Action action = d.step() == StrikeLedger.Step.KICK ? Strike.Action.KICK : Strike.Action.BAN;
            ledger.record(id, new Strike(now, session.sessionKey(), action, mode, check));
            plugin.storage().saveStrikesAsync(ledger);
            String what = d.step() == StrikeLedger.Step.KICK
                    ? "KICK " + (d.previousKicks() + 1) + "/" + s.kicksBeforeBan()
                    : "BAN POR PERFIL";
            String msg = "[OBSERVAÇÃO] " + base + " — teria recebido " + what + " (nada foi executado)";
            session.lastOccurrence(msg);
            report(s, msg, "&e[ZVB] &7[Observação] &f" + session.name() + " &7teria recebido &e" + what
                    + " &7por &f" + check);
            return;
        }

        Player player = Bukkit.getPlayer(id);
        boolean online = player != null && plugin.isCurrentSession(id, session.sessionKey());

        if (d.step() == StrikeLedger.Step.KICK) {
            if (!online) {
                String msg = "Ocorrência sem kick confirmado (jogador já saiu): " + base;
                session.lastOccurrence(msg);
                report(s, msg, "&e[ZVB] &f" + session.name() + " &7saiu antes do kick; ocorrência não contada.");
                return;
            }
            player.kick(ZwoVulcanBridgePlugin.text(fill(s.kickMessage(), session, flag)));
            ledger.record(id, new Strike(now, session.sessionKey(), Strike.Action.KICK, mode, check));
            plugin.storage().saveStrikesAsync(ledger);
            String msg = "KICK " + (d.previousKicks() + 1) + "/" + s.kicksBeforeBan() + " confirmado: " + base;
            session.lastOccurrence(msg);
            report(s, msg, "&c[ZVB] &f" + session.name() + " &7kickado (&c" + (d.previousKicks() + 1) + "/"
                    + s.kicksBeforeBan() + "&7) por &f" + check);
            return;
        }

        // Terceira ocorrência válida: ban por perfil.
        if (!s.banVerdict().allowed()) {
            if (online) {
                player.kick(ZwoVulcanBridgePlugin.text(fill(s.pendingBanKickMessage(), session, flag)));
            }
            String msg = "BAN PENDENTE (revisão manual; " + s.banVerdict().reason() + "): " + base;
            session.lastOccurrence(msg);
            report(s, msg, "&4[ZVB] &f" + session.name() + " &catingiu o ban por perfil. &7Revisão manual: "
                    + s.banVerdict().reason());
            return;
        }
        String command = fill(s.banCommand(), session, flag).replace("{reason}", fill(s.banReason(), session, flag));
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        boolean ok;
        try {
            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok) {
            ledger.record(id, new Strike(now, session.sessionKey(), Strike.Action.BAN, mode, check));
            plugin.storage().saveStrikesAsync(ledger);
        }
        Player still = Bukkit.getPlayer(id);
        if (still != null) {
            String kick = ok ? s.banKickMessage() : s.pendingBanKickMessage();
            still.kick(ZwoVulcanBridgePlugin.text(fill(kick, session, flag)));
        }
        String msg = (ok ? "BAN POR PERFIL aplicado" : "FALHA ao executar o ban") + " (/" + command + "): " + base;
        session.lastOccurrence(msg);
        report(s, msg, (ok ? "&4[ZVB] &f" + session.name() + " &cbanido (perfil) por &f" + check
                : "&4[ZVB] &cFalha ao banir &f" + session.name() + "&c; verifique o console."));
    }

    private static String fill(String template, PlayerSession session, FlagInfo flag) {
        return template
                .replace("{player}", session.name())
                .replace("{uuid}", session.playerId().toString())
                .replace("{check}", flag.checkName() + " " + Character.toUpperCase(flag.checkType()));
    }

    private void report(BridgeSettings s, String logLine, String staffLine) {
        plugin.getLogger().info(logLine);
        if (s.auditLog()) {
            plugin.storage().audit(logLine);
        }
        if (s.alertStaff()) {
            var component = ZwoVulcanBridgePlugin.text(staffLine);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(ALERT_PERMISSION)) {
                    p.sendMessage(component);
                }
            }
        }
    }
}
