package dev.zwo.pingstate;

import dev.zwo.pingstate.core.CollectorStats;
import dev.zwo.pingstate.core.PingSettings;
import dev.zwo.pingstate.core.PingSnapshot;
import dev.zwo.pingstate.core.PingState;
import dev.zwo.pingstate.core.PingStatus;
import dev.zwo.pingstate.core.PingTracker;
import dev.zwo.pingstate.core.PlayerEntry;
import dev.zwo.pingstate.core.ServerHealth;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /pingstate — diagnóstico para a staff. Só lê o estado pronto (snapshots e contadores);
 * não dispara coleta nem recalcula nada.
 */
final class PingStateCommand implements TabExecutor {

    private static final String PERM_VIEW = "zwopingstate.staff";
    private static final String PERM_ADMIN = "zwopingstate.admin";

    private final ZwoPingStatePlugin plugin;

    PingStateCommand(ZwoPingStatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM_VIEW)) {
            send(sender, "&cSem permissão.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stats", "status" -> stats(sender);
            case "reload" -> {
                if (!sender.hasPermission(PERM_ADMIN)) {
                    send(sender, "&cSem permissão.");
                    return true;
                }
                plugin.reloadSettings();
                send(sender, "&aConfiguração recarregada.");
            }
            case "jogador", "player" -> {
                if (args.length < 2) {
                    send(sender, "&cUso: /" + label + " jogador <nick>");
                } else {
                    player(sender, args[1]);
                }
            }
            default -> player(sender, args[0]);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        send(s, "&6ZwoPingState &7— comandos:");
        send(s, "&e/" + label + " <nick> &7— estado, motivo, idade da amostra e métricas");
        send(s, "&e/" + label + " stats &7— custo da coleta, saúde do servidor, idade das amostras");
        send(s, "&e/" + label + " reload &7— recarrega o config.yml (admin)");
    }

    private void player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            send(s, "&cJogador offline: " + name);
            return;
        }
        PingTracker t = plugin.tracker();
        long now = System.nanoTime();
        PlayerEntry e = t.entry(p.getUniqueId());
        PingSnapshot snap = e == null ? null : e.snapshot();
        PingStatus answer = t.query(p.getUniqueId(), now); // exatamente o que a ponte recebe agora
        PingSettings st = t.settings();

        send(s, "&6" + p.getName() + (e != null && e.bedrock() ? " &b[Bedrock]" : "") + " &7— resposta atual: "
                + color(answer.state()) + answer.state() + " &7(" + answer.reason().description() + ")");
        if (snap == null) {
            send(s, "&7Sem registro na coleta.");
            return;
        }
        send(s, "&7Avaliação da coleta: " + color(snap.status().state()) + snap.status().state() + " &7("
                + snap.status().reason().description() + ") &8| &7calculada há &f" + ms(now - snap.evaluatedAtNanos()) + " ms");
        send(s, "&7Idade da última amostra: &f" + (snap.lastSampleNanos() == 0 ? "nenhuma" : ms(now - snap.lastSampleNanos()) + " ms")
                + " &8| &7conectado há &f" + ((now - e.joinedAtNanos()) / 1_000_000_000L) + " s");
        if (snap.samples() > 0) {
            send(s, "&7Ping: último &f" + snap.lastPing() + " &7| média &f" + snap.averagePing() + " &7| mín &f"
                    + snap.minPing() + " &7| máx &f" + snap.maxPing() + " &7ms");
            send(s, "&7Oscilação (jitter): &f" + snap.jitter() + " ms &8| &7picos: &f" + snap.spikes()
                    + " &8| &7amostras: &f" + snap.samples() + "/" + st.windowSize() + " (mín. " + st.minSamples() + ")");
        } else {
            send(s, "&7Amostras válidas: &f0/" + st.windowSize() + " (mín. " + st.minSamples() + ")");
        }
        if (snap.recoveryRemainingNanos() > 0) {
            send(s, "&7Recuperação: faltam &f" + ms(snap.recoveryRemainingNanos()) + " ms &7de período bom contínuo");
        }
        send(s, "&7Limites: ping &f" + st.maxPing() + "&7, média &f" + st.maxAveragePing() + "&7, jitter &f"
                + st.maxJitter() + "&7, amplitude &f" + st.maxRange() + "&7, salto de pico &f" + st.spikeJump() + "&7, picos tolerados &f" + st.maxSpikes());
        serverLine(s, t, now);
    }

    private void stats(CommandSender s) {
        PingTracker t = plugin.tracker();
        PingSettings st = t.settings();
        CollectorStats c = t.stats();
        long now = System.nanoTime();

        long oldest = 0;
        long sum = 0;
        int sampled = 0;
        int stable = 0, unstable = 0, unknown = 0;
        for (PlayerEntry e : t.entries()) {
            PingSnapshot snap = e.snapshot();
            if (snap.lastSampleNanos() != 0) {
                long age = now - snap.lastSampleNanos();
                oldest = Math.max(oldest, age);
                sum += age;
                sampled++;
            }
            switch (t.query(e.id(), now).state()) {
                case STABLE -> stable++;
                case UNSTABLE -> unstable++;
                case UNKNOWN -> unknown++;
            }
        }
        send(s, "&6ZwoPingState " + plugin.getPluginMeta().getVersion() + " &7— "
                + (plugin.bridgeRegistered() ? "&aprovedor registrado na ponte" : "&eponte ausente"));
        send(s, "&7Jogadores: &f" + t.size() + " &8(&a" + stable + " STABLE &c" + unstable + " UNSTABLE &e" + unknown + " UNKNOWN&8)");
        send(s, "&7Lote: &f" + t.currentBatchSize() + " &7jogadores/tick (máx. " + st.maxSamplesPerTick()
                + ") &8| &7intervalo: &f" + st.sampleIntervalTicks() + " ticks &8| &7amostras/s: &f"
                + String.format(Locale.ROOT, "%.1f", c.samplesPerSecond()));
        send(s, "&7Custo da coleta por tick: média &f" + us(c.minuteAverageNanos()) + " µs &7(1 min) | EMA &f"
                + us((long) c.emaNanosPerTick()) + " µs &7| máx. &f" + us(c.minuteMaxNanos()) + " µs");
        send(s, "&7Orçamento estourado: &f" + c.budgetHits() + "x &8| &7erros de leitura: &f" + c.errors());
        send(s, "&7Idade das amostras: média &f" + (sampled == 0 ? "-" : ms(sum / sampled)) + " ms &7| mais antiga &f"
                + (sampled == 0 ? "-" : ms(oldest)) + " ms &7(vence em " + ms(st.staleNanos()) + " ms)");
        serverLine(s, t, now);
    }

    private void serverLine(CommandSender s, PingTracker t, long now) {
        ServerHealth h = t.health();
        PingSettings st = t.settings();
        boolean unstable = h.unstable(now, st);
        StringBuilder sb = new StringBuilder("&7Servidor: ")
                .append(unstable ? "&cINSTÁVEL" : "&aestável")
                .append(" &8| &7TPS curto &f").append(String.format(Locale.ROOT, "%.2f", h.shortTps()))
                .append(" &7| MSPT &f").append(String.format(Locale.ROOT, "%.1f", h.mspt()));
        if (h.everStalled()) {
            sb.append(" &7| última travada &f").append(ms(h.lastStallLengthNanos())).append(" ms &7há &f")
                    .append((now - h.lastStallNanos()) / 1_000_000_000L).append(" s");
        }
        if (unstable) {
            sb.append(" &7| causa &f").append(h.lastCause()).append(" (")
                    .append(String.format(Locale.ROOT, "%.1f", h.lastCauseValue())).append(")");
        }
        send(s, sb.toString());
    }

    private static String color(PingState st) {
        return switch (st) {
            case STABLE -> "&a";
            case UNSTABLE -> "&c";
            case UNKNOWN -> "&e";
        };
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000L;
    }

    private static String us(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1000.0);
    }

    private static void send(CommandSender s, String legacy) {
        s.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(legacy));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERM_VIEW) || args.length != 1) {
            return List.of();
        }
        String p = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : List.of("stats", "reload")) {
            if (o.startsWith(p)) out.add(o);
        }
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
        }
        return out;
    }
}
