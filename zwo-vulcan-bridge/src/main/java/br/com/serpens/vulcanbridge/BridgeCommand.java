package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import br.com.serpens.vulcanbridge.core.Strike;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /zvb — diagnóstico e administração da ponte (somente staff). */
final class BridgeCommand implements TabExecutor {

    private static final String PERM_VIEW = "zwovulcanbridge.staff";
    private static final String PERM_ADMIN = "zwovulcanbridge.admin";
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

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
            case "strikes", "historico" -> strikes(sender, args);
            case "limpar", "clear" -> clear(sender, args);
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
        send(s, "&e/" + label + " status &7— estado geral da ponte");
        send(s, "&e/" + label + " jogador <nick> &7— sequência, conexão e proteções do jogador");
        send(s, "&e/" + label + " strikes <nick|uuid> &7— histórico de ocorrências");
        send(s, "&e/" + label + " limpar <nick|uuid> &7— apaga o histórico (admin)");
        send(s, "&e/" + label + " reload &7— recarrega o config.yml (admin)");
    }

    private void status(CommandSender s) {
        BridgeSettings st = plugin.settings();
        VulcanHook v = plugin.vulcan();
        send(s, "&6ZwoVulcanBridge " + plugin.getPluginMeta().getVersion());
        send(s, "&7Modo: " + (st.mode() == br.com.serpens.vulcanbridge.core.BridgeMode.OBSERVE
                ? "&eOBSERVE &7(ninguém é punido)" : "&cENFORCE &7(punições ativas)"));
        send(s, "&7Vulcan: " + (v.active() ? "&aconectado &7via " + v.eventName() : "&cdesconectado")
                + " &8| &7punições internas do Vulcan: " + (st.cancelVulcanPunishments() ? "&ebloqueadas" : "&cliberadas"));
        send(s, "&7Provedor de conexão: " + (plugin.provider().available() ? "&a" : "&c") + plugin.provider().describe()
                + " &8| &7falhas: &f" + plugin.provider().failures());
        send(s, "&7Flags recebidas: &f" + v.flagsReceived() + " &8| &7avaliadas: &f" + plugin.engine().flagsSeen()
                + " &8| &7ocorrências: &f" + plugin.engine().occurrences());
        send(s, "&7Flags descartadas: conexão &f" + plugin.engine().flagsDiscardedConnection()
                + " &7| proteção &f" + plugin.engine().flagsDiscardedGrace()
                + " &8| &7sequências descartadas: &f" + plugin.engine().sequencesDiscarded());
        send(s, "&7Punições do Vulcan bloqueadas: &f" + v.punishCancelled() + " &8| &7erros de leitura: &f" + v.extractionErrors());
        send(s, "&7Sessões ativas: &f" + plugin.sessions().size() + " &8| &7perfis com histórico: &f" + plugin.ledger().size());
        send(s, "&7Ban automático: " + (st.banVerdict().allowed() ? "&a" + st.banCommand() : "&e" + st.banVerdict().reason()));
    }

    private void player(CommandSender s, String[] args) {
        if (args.length < 2) {
            send(s, "&cUso: /zvb jogador <nick>");
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            send(s, "&cJogador offline. Para o histórico use /zvb strikes " + args[1]);
            return;
        }
        PlayerSession session = plugin.sessions().get(p.getUniqueId());
        if (session == null) {
            send(s, "&cSem sessão registrada para " + p.getName());
            return;
        }
        long now = System.currentTimeMillis();
        PlayerSession.View view = session.view();
        ConnectionStatus c = plugin.provider().status(p.getUniqueId());
        send(s, "&6" + p.getName() + " &7(" + p.getUniqueId() + ")" + (session.bedrock() ? " &b[Bedrock: excluído]" : ""));
        send(s, "&7Conexão: " + color(c) + c.state() + " &7— " + c.reason().description());
        send(s, "&7Sequência atual: &f" + view.sequence() + "/" + plugin.settings().policy().requiredHighFlags()
                + " &8| &7ocorrência nesta sessão: &f" + (view.occurrenceThisSession() ? "sim" : "não"));
        send(s, "&7Proteção ativa: " + (session.inGrace(now)
                ? "&e" + session.graceCause() + " (" + (session.graceUntilMillis() - now) + " ms)" : "&fnão"));
        send(s, "&7Sequências descartadas: &f" + view.discardedSequences()
                + (view.lastDiscardReason().isEmpty() ? "" : " &8| &7último motivo: &f" + view.lastDiscardReason()
                + " &7(" + ((now - view.lastDiscardAtMillis()) / 1000) + "s atrás)"));
        if (!view.lastOccurrence().isEmpty()) {
            send(s, "&7Última ocorrência: &f" + view.lastOccurrence());
        }
        send(s, "&7Kicks válidos (" + plugin.settings().mode() + "): &f"
                + plugin.ledger().validKicks(p.getUniqueId(), plugin.settings().mode(), now)
                + "/" + plugin.settings().kicksBeforeBan());
    }

    private void strikes(CommandSender s, String[] args) {
        if (args.length < 2) {
            send(s, "&cUso: /zvb strikes <nick|uuid>");
            return;
        }
        UUID id = resolve(args[1]);
        if (id == null) {
            send(s, "&cJogador desconhecido: " + args[1]);
            return;
        }
        List<Strike> list = plugin.ledger().history(id);
        send(s, "&6Histórico de " + args[1] + " &7(" + id + "): &f" + list.size() + " registro(s)");
        for (Strike st : list) {
            send(s, "&8- &7" + TS.format(Instant.ofEpochMilli(st.atMillis())) + " &f" + st.action() + " &7["
                    + st.mode() + "] &f" + st.check() + " &8(sessão " + st.sessionKey().substring(0, 8) + ")");
        }
    }

    private void clear(CommandSender s, String[] args) {
        if (!s.hasPermission(PERM_ADMIN)) {
            send(s, "&cSem permissão.");
            return;
        }
        if (args.length < 2) {
            send(s, "&cUso: /zvb limpar <nick|uuid>");
            return;
        }
        UUID id = resolve(args[1]);
        if (id == null) {
            send(s, "&cJogador desconhecido: " + args[1]);
            return;
        }
        if (plugin.ledger().clear(id)) {
            plugin.storage().saveStrikesAsync(plugin.ledger());
            plugin.storage().audit("Histórico de " + args[1] + " (" + id + ") apagado por " + s.getName());
            send(s, "&aHistórico apagado.");
        } else {
            send(s, "&7Nada para apagar.");
        }
    }

    private static UUID resolve(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            // não é UUID
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        return cached == null ? null : cached.getUniqueId();
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
            return filter(List.of("status", "jogador", "strikes", "limpar", "reload"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status") && !args[0].equalsIgnoreCase("reload")) {
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
