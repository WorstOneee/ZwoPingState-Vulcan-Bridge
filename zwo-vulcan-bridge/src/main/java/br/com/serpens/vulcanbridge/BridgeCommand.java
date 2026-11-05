package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.CheckRule;
import br.com.serpens.vulcanbridge.core.DurationText;
import br.com.serpens.vulcanbridge.core.Escalation;
import br.com.serpens.vulcanbridge.core.Ladder;
import br.com.serpens.vulcanbridge.core.OffenderRecord;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** /zvb status | jogador | historico | limpar | reload */
final class BridgeCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("status", "jogador", "historico", "limpar", "reload");

    private final ZwoVulcanBridgePlugin plugin;

    BridgeCommand(ZwoVulcanBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("zwovulcanbridge.staff")) {
            msg(sender, "&cSem permissão.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "jogador" -> player(sender, args);
            case "historico", "histórico" -> history(sender, args);
            case "limpar" -> clear(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("zwovulcanbridge.admin")) {
                    msg(sender, "&cSem permissão.");
                    return true;
                }
                plugin.reloadSettings();
                msg(sender, "&aConfiguração recarregada. Modo: &f" + plugin.settings().mode()
                        + " &7(evidências em andamento foram descartadas)");
                status(sender);
            }
            default -> msg(sender, "&7Uso: /" + label + " <status|jogador <nick>|historico <nick|uuid>|limpar <nick|uuid>|reload>");
        }
        return true;
    }

    private void status(CommandSender s) {
        BridgeSettings st = plugin.settings();
        Escalation e = plugin.escalation();
        ZwoVulcanBridgePlugin.Counters c = plugin.counters;
        VulcanHook v = plugin.vulcan();
        msg(s, "&6ZwoVulcanBridge " + plugin.getPluginMeta().getVersion() + " &7— escalada por UUID (kick, kick, ban "
                + DurationText.format(st.banDurationMillis()) + ")");
        msg(s, "&7Modo: " + (st.mode() == BridgeMode.ENFORCE ? "&cENFORCE (aplica)" : "&eOBSERVE (só registra)"));
        List<String> blockers = plugin.automationBlockers();
        if (blockers.isEmpty()) {
            msg(s, "&7Automação: &aliberada" + (st.mode() == BridgeMode.OBSERVE ? " &7(mas o modo é OBSERVE)" : ""));
        } else {
            msg(s, "&7Automação: &cBLOQUEADA &7— " + String.join("; ", blockers));
        }
        msg(s, "&7Checks aprovados: &f" + st.policy().rules().size() + " &7em &f" + st.policy().families().size()
                + " &7família(s) | mínimo &f" + st.policy().minFamilies() + " &7famílias em &f"
                + st.policy().windowMillis() / 1000 + " s");
        if (!st.policy().rules().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (CheckRule r : st.policy().rules()) {
                parts.add(r.key() + "[" + r.family() + ", VL≥" + r.minVl() + ", x" + r.minFlags() + "]");
            }
            msg(s, "&8  " + String.join(" ", parts));
        }
        for (String p : st.problems()) {
            msg(s, "&e  config: " + p);
        }
        msg(s, "&7Vulcan: " + (v.flagHooked() ? "&aconectado (" + v.version() + ")" : "&c" + v.failure())
                + " &7| flags lidas &f" + v.flags() + " &7| punições do Vulcan canceladas &f" + v.punishCancelled()
                + " &7| erros de leitura &f" + v.extractionErrors());
        msg(s, "&7Provedor de conexão: &f" + plugin.provider().describe() + " &7| falhas: &f" + plugin.provider().failures());
        msg(s, "&7ModPanel: " + (plugin.banLink().backend() != null ? "&a" : "&c") + plugin.banLink().describe());
        msg(s, "&7Floodgate: &f" + plugin.bedrock().floodgateState()
                + (st.assumeJavaWithoutFloodgate() ? " &c(assume-java-without-floodgate ligado)" : ""));
        msg(s, "&7Evidências: aprovadas &f" + e.approvedFlags() + " &7| descartes por conexão &f" + e.connectionDiscards()
                + " &7| ignoradas (proteção) &f" + e.graceIgnored());
        msg(s, "&7Ocorrências: &f" + e.triggered() + " &7| observação &f" + c.observed.sum() + " &7| revalidação "
                + "descartou &f" + c.revalidationDiscards.sum() + " &7| saiu antes &f" + c.leftBeforeAction.sum()
                + " &7| bloqueadas &f" + c.blocked.sum() + " &7| revisão &f" + c.reviews.sum());
        msg(s, "&7Kicks: enviados &f" + c.kicksSent.sum() + " &7| confirmados &f" + c.kicksConfirmed.sum()
                + " &7| cancelados &f" + c.kicksCancelled.sum() + " &7| não confirmados &f" + c.kicksUnconfirmed.sum());
        msg(s, "&7Bans: pedidos &f" + c.bansRequested.sum() + " &7| aplicados &f" + c.bansApplied.sum()
                + " &7| para revisão &f" + c.bansReview.sum());
        HistoryStore h = plugin.history();
        msg(s, "&7Histórico: &f" + h.size() + " &7UUID(s) | validade &f" + DurationText.format(st.validityMillis())
                + (h.saveFailures() > 0 ? " &c| falhas de gravação " + h.saveFailures() + " (" + h.lastError() + ")" : "")
                + " &7| fila de I/O &f" + plugin.io().queued() + " &7(recusadas " + plugin.io().rejected()
                + ", auditoria perdida " + plugin.audit().dropped() + ")");
        msg(s, "&7Sessões ativas: &f" + plugin.sessions().size());
        VulcanConfigScanner.Report vr = plugin.vulcanConfig();
        if (!vr.punishableChecks().isEmpty()) {
            msg(s, "&eAtenção: " + vr.punishableChecks().size() + " check(s) do Vulcan com punishable: true. "
                    + "A ponte cancela, mas desligue no config do Vulcan.");
        }
        if (!vr.ipBanCommands().isEmpty()) {
            msg(s, "&eAtenção: o config do Vulcan tem comando(s) que banem IP: " + String.join(" | ", vr.ipBanCommands()));
        }
        if (c.vulcanPunishWarnings.sum() > 0) {
            msg(s, "&eO Vulcan tentou punir " + c.vulcanPunishWarnings.sum() + " vez(es) (canceladas pela ponte).");
        }
    }

    private void player(CommandSender s, String[] args) {
        if (args.length < 2) {
            msg(s, "&7Uso: /zvb jogador <nick>");
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            msg(s, "&cJogador offline. Use /zvb historico " + args[1]);
            return;
        }
        PlayerSession ses = plugin.sessions().get(p.getUniqueId());
        ConnectionStatus c = plugin.provider().status(p.getUniqueId());
        long now = System.currentTimeMillis();
        msg(s, "&6" + p.getName() + " &8(" + p.getUniqueId() + ")");
        msg(s, "&7Conexão: &f" + c.state() + " — " + c.reason().description());
        if (ses == null) {
            msg(s, "&cSem sessão na ponte.");
            return;
        }
        msg(s, "&7Conta: &f" + ses.kind().description() + " &8(" + ses.kindDetail() + ")");
        msg(s, "&7Proteção: " + (ses.inGrace(now) ? "&e" + ses.graceCause() + " (" + (ses.graceUntilMillis() - now) + " ms)"
                : "&fnenhuma"));
        msg(s, "&7Sessão: estágio &f" + ses.stage() + " &7| evidências na janela &f" + ses.evidenceCount()
                + " &7| flags aprovadas &f" + ses.approvedFlags() + " &7| descartes &f" + ses.discards()
                + (ses.discards() > 0 ? " &8(último: " + discardText(ses) + ", " + (now - ses.lastDiscardAtMillis()) / 1000
                + " s atrás)" : ""));
        if (!ses.lastDecision().isEmpty()) {
            msg(s, "&7Decisão nesta sessão: &f" + ses.lastDecision());
        }
        showRecord(s, p.getUniqueId(), ses.sessionId());
    }

    private static String discardText(PlayerSession ses) {
        ConnectionStatus st = ses.lastDiscardStatus();
        return st == null ? ses.lastDiscardCause() : "conexão " + st.state() + "/" + st.reason();
    }

    private void history(CommandSender s, String[] args) {
        if (args.length < 2) {
            msg(s, "&7Uso: /zvb historico <nick|uuid>");
            return;
        }
        Optional<UUID> id = resolve(args[1]);
        if (id.isEmpty()) {
            msg(s, "&cNenhum histórico para " + args[1] + ".");
            return;
        }
        msg(s, "&6Histórico de " + args[1] + " &8(" + id.get() + ")");
        showRecord(s, id.get(), 0L);
    }

    private void showRecord(CommandSender s, UUID id, long sessionId) {
        BridgeSettings st = plugin.settings();
        long now = System.currentTimeMillis();
        OffenderRecord r = plugin.history().get(id).filter(x -> !x.expired(now, st.validityMillis())).orElse(null);
        if (r == null) {
            msg(s, "&7Histórico: &fnenhuma ocorrência válida &7| próximo passo: &f" + Ladder.Step.KICK_1.description());
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm");
        msg(s, "&7Histórico: &f" + r.kicks() + " &7kick(s) confirmado(s) | 1º em &f" + f.format(new Date(r.firstKickAt()))
                + " &7| vale até &f" + f.format(new Date(r.firstKickAt() + st.validityMillis())));
        msg(s, "&7Ban: &f" + r.ban().description() + (r.banDetail().isEmpty() ? "" : " &8(" + r.banDetail() + ")"));
        msg(s, "&7Próximo passo: &f" + Ladder.next(r, now, st.validityMillis(), sessionId).description());
    }

    private void clear(CommandSender s, String[] args) {
        if (!s.hasPermission("zwovulcanbridge.admin")) {
            msg(s, "&cSem permissão.");
            return;
        }
        if (args.length < 2) {
            msg(s, "&7Uso: /zvb limpar <nick|uuid>");
            return;
        }
        Optional<UUID> id = resolve(args[1]);
        if (id.isEmpty() || !plugin.clearHistory(id.get(), s.getName())) {
            msg(s, "&cNenhum histórico para " + args[1] + ".");
            return;
        }
        msg(s, "&aHistórico de " + args[1] + " zerado. (Isso não remove bans do ModPanel: use /mpunban.)");
    }

    /** Nick online, UUID ou nick salvo no histórico. Sem consulta à Mojang. */
    private Optional<UUID> resolve(String input) {
        if (input.length() == 36) {
            try {
                return Optional.of(UUID.fromString(input));
            } catch (IllegalArgumentException ignored) {
                // tenta como nick
            }
        }
        Player p = Bukkit.getPlayerExact(input);
        if (p != null) {
            return Optional.of(p.getUniqueId());
        }
        for (OffenderRecord r : plugin.history().all()) {
            if (r.name().equalsIgnoreCase(input)) {
                return Optional.of(r.playerId());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBS.stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status") && !args[0].equalsIgnoreCase("reload")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    private static void msg(CommandSender s, String legacy) {
        s.sendMessage(ZwoVulcanBridgePlugin.text(legacy));
    }
}
