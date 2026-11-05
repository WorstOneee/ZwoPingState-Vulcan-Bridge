package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.Gate;
import br.com.serpens.vulcanbridge.core.GateReason;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /zvb — diagnóstico da ponte (somente staff). Só lê contadores prontos. */
final class BridgeCommand implements TabExecutor {

    private static final String PERM_VIEW = "zwovulcanbridge.staff";
    private static final String PERM_ADMIN = "zwovulcanbridge.admin";

    private final ZwoVulcanBridgePlugin plugin;

    BridgeCommand(ZwoVulcanBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM_VIEW)) {
            send(sender, "&cSem permissão.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "jogador", "player" -> player(sender, args);
            case "reload" -> {
                if (!sender.hasPermission(PERM_ADMIN)) {
                    send(sender, "&cSem permissão.");
                    return true;
                }
                plugin.reloadSettings();
                send(sender, "&aConfiguração recarregada. Modo: &f" + plugin.settings().mode());
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        send(s, "&6ZwoVulcanBridge &7— comandos:");
        send(s, "&e/" + label + " status &7— estado geral do filtro");
        send(s, "&e/" + label + " jogador <nick> &7— conexão, proteção e últimas decisões do jogador");
        send(s, "&e/" + label + " reload &7— recarrega o config.yml (admin)");
    }

    private void status(CommandSender s) {
        BridgeSettings st = plugin.settings();
        VulcanHook v = plugin.vulcan();
        Gate g = plugin.gate();
        send(s, "&6ZwoVulcanBridge " + plugin.getPluginMeta().getVersion() + " &7— filtro de lag para as punições do Vulcan");
        send(s, "&7Modo: " + (st.mode() == BridgeMode.OBSERVE
                ? "&eOBSERVE &7(toda punição do Vulcan é bloqueada e registrada)"
                : "&cENFORCE &7(o Vulcan pune quando a conexão está STABLE)")
                + " &8| &7cancelar flags com lag: &f" + (st.policy().cancelFlags() ? "sim" : "não")
                + " &8| &7lag recente: &f" + (st.policy().recentLagMillis() / 1000) + " s");
        send(s, "&7Vulcan: " + (v.active() ? "&aconectado &7(" + v.version() + ")" : "&cdesconectado — nada é filtrado")
                + " &8| &7VL zerado após bloqueio: &f" + v.vlResets() + " &8| &7erros de leitura: &f" + v.extractionErrors());
        send(s, "&7Provedor de conexão: " + (plugin.provider().available() ? "&a" : "&c") + plugin.provider().describe()
                + " &8| &7falhas: &f" + plugin.provider().failures());
        send(s, "&7Flags: &f" + g.flagsSeen() + " &7vistas | &f" + g.flagsCancelled() + " &7canceladas por lag/proteção"
                + (st.mode() == BridgeMode.OBSERVE ? " | &f" + g.flagsWouldCancel() + " &7seriam canceladas" : ""));
        send(s, "&7Punições do Vulcan: &f" + g.punishSeen() + " &7| liberadas &f" + g.punishAllowed()
                + " &7| bloqueadas &f" + g.punishBlockedTotal());
        send(s, "&7  bloqueios: observação &f" + g.punishBlocked(GateReason.OBSERVE_MODE)
                + " &7| instável &f" + g.punishBlocked(GateReason.CONNECTION_UNSTABLE)
                + " &7| desconhecida &f" + g.punishBlocked(GateReason.CONNECTION_UNKNOWN)
                + " &7| lag recente &f" + g.punishBlocked(GateReason.RECENT_LAG)
                + " &7| sem provedor &f" + g.punishBlocked(GateReason.PROVIDER_UNAVAILABLE)
                + " &7| proteção &f" + g.punishBlocked(GateReason.PROTECTION)
                + " &7| Bedrock &f" + g.punishBlocked(GateReason.BEDROCK));
        send(s, "&7Sessões ativas: &f" + plugin.sessions().size());
        List<String> ip = plugin.vulcanIpBanCommands();
        if (!ip.isEmpty()) {
            send(s, "&cAtenção: o config do Vulcan tem comando(s) que banem IP: &f" + String.join(" &8| &f", ip)
                    + " &7— use &f/mpbanperfil %player% <motivo>");
        }
    }

    private void player(CommandSender s, String[] args) {
        if (args.length < 2) {
            send(s, "&cUso: /zvb jogador <nick>");
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            send(s, "&cJogador offline.");
            return;
        }
        PlayerSession session = plugin.sessions().get(p.getUniqueId());
        if (session == null) {
            send(s, "&cSem sessão registrada para " + p.getName());
            return;
        }
        long now = System.currentTimeMillis();
        ConnectionStatus c = plugin.provider().status(p.getUniqueId());
        send(s, "&6" + p.getName() + " &7(" + p.getUniqueId() + ")" + (session.bedrock() ? " &b[Bedrock: nunca punido]" : ""));
        send(s, "&7Conexão: " + color(c) + c.state() + " &7— " + c.reason().description()
                + " &8| &7punição do Vulcan agora: " + (wouldPass(session, c, now) ? "&cpassaria" : "&abloqueada"));
        send(s, "&7Proteção ativa: " + (session.inGrace(now)
                ? "&e" + session.graceCause() + " (" + (session.graceUntilMillis() - now) + " ms)" : "&fnão"));
        send(s, "&7Flags filtradas nesta sessão: &f" + session.flagsFiltered()
                + (session.lastFlagReason() == null ? "" : " &8| &7último motivo: &f" + session.lastFlagReason().description()
                + " &7(" + ((now - session.lastFlagAtMillis()) / 1000) + "s atrás)"));
        send(s, "&7Punições do Vulcan nesta sessão: liberadas &f" + session.punishmentsAllowed()
                + " &7| bloqueadas &f" + session.punishmentsBlocked()
                + (session.lastPunishReason() == null ? "" : " &8| &7última: &f" + session.lastPunishReason().description()));
    }

    private boolean wouldPass(PlayerSession session, ConnectionStatus c, long now) {
        BridgeSettings st = plugin.settings();
        boolean recentLag = !st.policy().cancelFlags() && st.policy().recentLagMillis() > 0
                && session.lastLagFlagAtMillis() != 0 && now - session.lastLagFlagAtMillis() < st.policy().recentLagMillis();
        return st.mode() == BridgeMode.ENFORCE
                && !(session.bedrock() && st.policy().excludeBedrock())
                && !session.inGrace(now)
                && !recentLag
                && c.state() == br.com.serpens.vulcanbridge.api.ConnectionState.STABLE;
    }

    private static String color(ConnectionStatus c) {
        return switch (c.state()) {
            case STABLE -> "&a";
            case UNSTABLE -> "&c";
            case UNKNOWN -> "&e";
        };
    }

    private static void send(CommandSender s, String legacy) {
        s.sendMessage(ZwoVulcanBridgePlugin.text(legacy));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERM_VIEW)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("status", "jogador", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("jogador")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
