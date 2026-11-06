package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionSnapshot;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.AccountKind;
import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.DurationText;
import br.com.serpens.vulcanbridge.core.Escalation;
import br.com.serpens.vulcanbridge.core.FlagInfo;
import br.com.serpens.vulcanbridge.core.Ladder;
import br.com.serpens.vulcanbridge.core.OffenderRecord;
import br.com.serpens.vulcanbridge.core.OffenderRecord.BanState;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * ZwoVulcanBridge 3: a ponte controla a escalada.
 *
 * <p>As evidências vêm das flags do Vulcan (com os comandos automáticos do Vulcan desligados).
 * Uma ocorrência só vale com flags altas e repetidas de checks aprovados, de pelo menos duas
 * famílias, numa janela de tempo, com a conexão STABLE o tempo todo (ZwoPingState) e a conta Java
 * confirmada. Sequência por UUID: kick, kick (em outra sessão), ban temporário só do perfil pelo
 * ModPanel. No modo OBSERVE (padrão) nada é aplicado.</p>
 */
public final class ZwoVulcanBridgePlugin extends JavaPlugin implements Listener, VulcanHook.Sink {

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private volatile BridgeSettings settings;
    private Escalation escalation;
    private ProviderTracker provider;
    private VulcanHook vulcan;
    private BedrockDetector bedrock;
    private BanLink banLink;
    private IoWorker io;
    private AuditLog audit;
    private HistoryStore history;
    private volatile VulcanConfigScanner.Report vulcanConfig = VulcanConfigScanner.Report.NONE;

    final Counters counters = new Counters();

    /** Contadores de decisões (não de flags). */
    static final class Counters {
        final LongAdder observed = new LongAdder();
        final LongAdder revalidationDiscards = new LongAdder();
        final LongAdder leftBeforeAction = new LongAdder();
        final LongAdder reviews = new LongAdder();
        final LongAdder blocked = new LongAdder();
        final LongAdder kicksSent = new LongAdder();
        final LongAdder kicksConfirmed = new LongAdder();
        final LongAdder kicksCancelled = new LongAdder();
        final LongAdder kicksUnconfirmed = new LongAdder();
        final LongAdder bansRequested = new LongAdder();
        final LongAdder bansApplied = new LongAdder();
        final LongAdder bansReview = new LongAdder();
        final LongAdder scheduleFailures = new LongAdder();
        final LongAdder vulcanPunishWarnings = new LongAdder();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = BridgeSettings.load(getConfig());
        escalation = new Escalation(settings.policy());
        io = new IoWorker();
        audit = new AuditLog(getDataFolder(), io, getLogger());
        history = new HistoryStore(getDataFolder(), io, getLogger());
        int loaded = history.load(System.currentTimeMillis(), settings.validityMillis());

        provider = new ProviderTracker();
        provider.refresh();
        bedrock = BedrockDetector.create(getLogger());
        banLink = new BanLink();
        banLink.refresh();

        getServer().getPluginManager().registerEvents(provider, this);
        getServer().getPluginManager().registerEvents(banLink, this);
        getServer().getPluginManager().registerEvents(this, this);

        vulcan = new VulcanHook(this, getLogger(), this);
        vulcan.hook(getServer().getPluginManager());

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            openSession(p, now);
        }

        BridgeCommand cmd = new BridgeCommand(this);
        PluginCommand pc = getCommand("zwovulcanbridge");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        // Manutenção: vencidos e nova tentativa de gravação. Um timer só, a cada 5 min.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            history.purgeExpired(System.currentTimeMillis(), settings.validityMillis());
            history.retryIfDirty();
        }, 6000L, 6000L);

        getLogger().info("Histórico: " + loaded + " UUID(s) com ocorrências válidas.");
        logStartup();
        // ModPanel e ZwoPingState podem ligar depois da ponte: confere de novo no primeiro tick.
        Bukkit.getScheduler().runTask(this, () -> {
            banLink.refresh();
            provider.refresh();
            List<String> b = automationBlockers();
            if (settings.mode() == BridgeMode.ENFORCE && !b.isEmpty()) {
                getLogger().warning("Modo ENFORCE, mas a automação está BLOQUEADA: " + String.join("; ", b));
            }
        });
    }

    @Override
    public void onDisable() {
        if (vulcan != null) {
            vulcan.unhook();
        }
        sessions.clear();
        if (io != null) {
            io.shutdown();
        }
        if (history != null) {
            history.flushSync();
        }
    }

    void reloadSettings() {
        reloadConfig();
        BridgeSettings s = BridgeSettings.load(getConfig());
        settings = s;
        escalation.policy(s.policy()); // nova geração: evidências antigas são descartadas
        banLink.refresh();
        provider.refresh();
        if (!vulcan.active()) {
            vulcan.hook(getServer().getPluginManager());
        }
        logStartup();
    }

    private void logStartup() {
        BridgeSettings s = settings;
        getLogger().info("Modo: " + s.mode() + (s.mode() == BridgeMode.OBSERVE
                ? " (observação: nada é aplicado, só registrado)"
                : " (aplica kick, kick e ban temporário por UUID)"));
        getLogger().info("Checks aprovados: " + s.policy().rules().size() + " em " + s.policy().families().size()
                + " família(s); mínimo de " + s.policy().minFamilies() + " famílias em "
                + s.policy().windowMillis() / 1000 + " s.");
        for (String p : s.problems()) {
            getLogger().warning("Config: " + p);
        }
        getLogger().info("ModPanel: " + banLink.describe());
        vulcanConfig = s.checkVulcanConfig()
                ? VulcanConfigScanner.scan(getServer().getPluginManager().getPlugin("Vulcan"))
                : VulcanConfigScanner.Report.NONE;
        if (!vulcanConfig.punishableChecks().isEmpty()) {
            getLogger().warning("O Vulcan tem " + vulcanConfig.punishableChecks().size() + " check(s) com punishable: true"
                    + " (ex.: " + String.join(", ", vulcanConfig.punishableChecks().subList(0,
                    Math.min(5, vulcanConfig.punishableChecks().size())))
                    + "). A ponte cancela essas punições, mas deixe punishable: false em todos.");
        }
        if (!vulcanConfig.ipBanCommands().isEmpty()) {
            getLogger().warning("O config do Vulcan tem comando(s) que podem banir IP: " + vulcanConfig.ipBanCommands());
        }
    }

    /** Motivos que impedem a automação no modo ENFORCE (vazio = pode agir). */
    List<String> automationBlockers() {
        List<String> out = new ArrayList<>();
        BridgeSettings s = settings;
        if (s.policy().rules().isEmpty()) {
            out.add("nenhum check aprovado em evidence.checks");
        } else if (!s.policy().canTrigger()) {
            out.add("checks aprovados de menos de " + s.policy().minFamilies() + " famílias");
        }
        if (!vulcan.flagHooked()) {
            out.add("Vulcan: " + (vulcan.failure().isEmpty() ? "não conectado" : vulcan.failure()));
        }
        if (!provider.available()) {
            out.add("provedor de conexão (ZwoPingState) indisponível");
        } else if (!provider.compatible()) {
            out.add("provedor de conexão INCOMPATÍVEL: sem snapshot com revisões (atualize o ZwoPingState para 1.1.0+)");
        }
        if (banLink.backend() == null) {
            out.add("ModPanel: " + banLink.describe());
        }
        return out;
    }

    // ------------------------------------------------------------------ evidências (qualquer thread)

    /** O(1): mapa + um snapshot do provedor + regra; agenda só quando a ocorrência dispara. */
    @Override
    public void onFlag(Player player, String checkName, char checkType, int vl) {
        UUID id = player.getUniqueId();
        PlayerSession s = sessions.get(id);
        if (s == null) {
            escalation.onFlag(null, null, 0L, checkName, checkType, vl, 0L);
            return;
        }
        long gen = provider.generation();
        ConnectionSnapshot snap = provider.snapshot(id);
        if (escalation.onFlag(s, snap, gen, checkName, checkType, vl, System.currentTimeMillis())
                == Escalation.FlagResult.TRIGGERED) {
            try {
                Bukkit.getScheduler().runTask(this, () -> act(s));
            } catch (IllegalPluginAccessException | IllegalStateException e) {
                counters.scheduleFailures.increment();
                s.resetToIdle();
            }
        }
    }

    private static final long VULCAN_WARN_INTERVAL = TimeUnit.MINUTES.toMillis(5);
    private volatile long lastVulcanWarn;

    @Override
    public void onVulcanPunish(Player player, FlagInfo check) {
        counters.vulcanPunishWarnings.increment();
        long now = System.currentTimeMillis();
        if (now - lastVulcanWarn >= VULCAN_WARN_INTERVAL) {
            lastVulcanWarn = now;
            String what = check == null ? "?" : check.display();
            getLogger().warning("O Vulcan tentou punir " + player.getName() + " (" + what + ") e a ponte cancelou. "
                    + "Deixe punishable: false nos checks do Vulcan. (aviso no máximo a cada 5 min)");
        }
    }

    // ------------------------------------------------------------------ ação (thread principal)

    private void act(PlayerSession s) {
        UUID id = s.playerId();
        long now = System.currentTimeMillis();
        BridgeSettings st = settings;
        String evidence = s.triggerSummary();
        Player p = Bukkit.getPlayer(id);
        if (p == null || sessions.get(id) != s) {
            counters.leftBeforeAction.increment();
            s.finish("saiu antes da ação");
            record(s.name(), id, "ocorrência não aplicada: o jogador saiu antes da ação", evidence);
            return;
        }

        // Revalidação imediatamente antes de agir: snapshot novo, conferido contra a âncora da
        // sequência (sessão de coleta e revisões). Lag detectado depois da última flag cancela.
        long gen = provider.generation();
        String why = Escalation.revalidate(s, provider.snapshot(id), gen, now);
        if (why != null) {
            counters.revalidationDiscards.increment();
            s.resetToIdle();
            s.decision("descartada na revalidação: " + why);
            record(s.name(), id, "ocorrência DESCARTADA na revalidação: " + why, evidence);
            return;
        }

        OffenderRecord rec = history.get(id).filter(r -> !r.expired(now, st.validityMillis())).orElse(null);
        Ladder.Step step = Ladder.next(rec, now, st.validityMillis(), s.sessionId());
        int kicks = rec == null ? 0 : rec.kicks();

        if (s.kind() != AccountKind.JAVA) {
            counters.reviews.increment();
            s.finish("só revisão: conta " + s.kind().description());
            review(p, "ocorrência registrada só para revisão — conta " + s.kind().description() + " ("
                    + s.kindDetail() + "); próximo passo seria: " + step.description(), evidence);
            return;
        }
        if (st.mode() == BridgeMode.OBSERVE) {
            counters.observed.increment();
            s.finish("[OBSERVE] teria aplicado: " + step.description());
            String line = "[OBSERVE] ocorrência válida; teria aplicado: " + step.description()
                    + " (kicks confirmados no histórico real: " + kicks + "; nada foi aplicado)";
            record(p.getName(), id, line, evidence);
            alert("&e" + p.getName() + " &7— " + line);
            return;
        }
        List<String> blockers = automationBlockers();
        if (!blockers.isEmpty()) {
            counters.blocked.increment();
            s.finish("automação bloqueada");
            review(p, "ocorrência válida, mas a automação está BLOQUEADA (" + String.join("; ", blockers)
                    + "); próximo passo seria: " + step.description(), evidence);
            return;
        }

        switch (step) {
            case KICK_1 -> kick(p, s, 1, st.kickMessage1(), evidence);
            case KICK_2 -> kick(p, s, 2, st.kickMessage2(), evidence);
            case TEMPBAN -> ban(p, s, Objects.requireNonNull(rec), evidence);
            case REVIEW_ONLY -> {
                counters.reviews.increment();
                s.finish("só revisão");
                review(p, "nova ocorrência válida, mas o histórico já está em '"
                        + (rec == null ? "?" : rec.ban().description()) + "': nada automático", evidence);
            }
        }
    }

    private void kick(Player p, PlayerSession s, int number, String message, String evidence) {
        counters.kicksSent.increment();
        s.beginKick(number);
        try {
            p.kick(text(message), PlayerKickEvent.Cause.PLUGIN);
        } finally {
            s.endKickCall();
        }
        if (!Boolean.TRUE.equals(s.kickAccepted())) {
            counters.kicksCancelled.increment();
            s.finish("kick " + number + " cancelado: não contou");
            review(p, "kick " + number + " foi CANCELADO por outro plugin (PlayerKickEvent): não contou", evidence);
            return;
        }
        s.finish("kick " + number + " enviado");
        record(p.getName(), p.getUniqueId(), "kick " + number + " enviado; conta quando a desconexão for confirmada",
                evidence);
        // Se em 5 s o jogador continuar na mesma sessão, o kick não aconteceu.
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (sessions.get(id) == s && Bukkit.getPlayer(id) != null) {
                counters.kicksUnconfirmed.increment();
                review(Bukkit.getPlayer(id), "kick " + number + " não desconectou o jogador em 5 s: não contou", "");
            }
        }, 100L);
    }

    private void ban(Player p, PlayerSession s, OffenderRecord rec, String evidence) {
        BridgeSettings st = settings;
        BanBackend backend = banLink.backend();
        if (backend == null) { // checado em automationBlockers, mas o ModPanel pode ter desligado agora
            counters.blocked.increment();
            s.finish("ModPanel indisponível");
            review(p, "3ª ocorrência válida, mas o ModPanel está indisponível: " + banLink.describe(), evidence);
            return;
        }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        String requestId = "zvb-" + id + "-" + rec.firstKickAt();
        history.put(rec.withBan(BanState.PENDING, now, requestId, "pedido enviado ao ModPanel"));
        counters.bansRequested.increment();
        s.finish("ban temporário pedido ao ModPanel");
        record(p.getName(), id, "3ª ocorrência: pedindo ban temporário de " + DurationText.format(st.banDurationMillis())
                + " só do perfil ao ModPanel (pedido " + requestId + ")", evidence);
        String name = p.getName();
        long sessionId = s.sessionId();
        try {
            backend.tempBanProfile(id, name, Duration.ofMillis(st.banDurationMillis()), st.banReason(), st.actorName(),
                            requestId)
                    .orTimeout(st.banTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .handle((o, err) -> o != null ? o
                            : BanBackend.Outcome.uncertain(err instanceof java.util.concurrent.TimeoutException
                            ? "sem resposta do ModPanel em " + st.banTimeoutMillis() / 1000 + " s"
                            : "erro: " + err))
                    .thenAccept(o -> mainThread(() -> onBanResult(id, name, sessionId, requestId, o)));
        } catch (Throwable t) {
            onBanResult(id, name, sessionId, requestId, BanBackend.Outcome.uncertain("falha ao chamar o ModPanel: " + t));
        }
    }

    private void onBanResult(UUID id, String name, long sessionId, String requestId, BanBackend.Outcome o) {
        OffenderRecord rec = history.get(id).orElse(null);
        boolean sameRequest = rec != null && requestId.equals(rec.banRequestId()) && rec.ban() == BanState.PENDING;
        String detail = o.status() + " #" + o.punishmentId() + " — " + o.detail();
        PlayerSession now = sessions.get(id);
        String where = now == null ? "offline" : now.sessionId() == sessionId ? "mesma sessão" : "outra sessão";
        if (o.confirmed() && sameRequest) {
            counters.bansApplied.increment();
            history.put(rec.withBan(BanState.APPLIED, System.currentTimeMillis(), requestId, detail));
            record(name, id, "ban temporário APLICADO e conferido pelo ModPanel (" + detail + "; jogador " + where + ")",
                    "");
            alert("&c" + name + " &7— ban temporário aplicado pelo ModPanel (registro #" + o.punishmentId() + ").");
            return;
        }
        counters.bansReview.increment();
        if (sameRequest) {
            history.put(rec.withBan(BanState.REVIEW, System.currentTimeMillis(), requestId, detail));
        }
        String why = o.confirmed() ? "o histórico mudou durante o pedido" : "resultado não confirmado";
        Player online = Bukkit.getPlayer(id);
        String line = "ban temporário NÃO confirmado (" + why + "): " + detail + " — marcado para revisão; "
                + "nenhum outro ban será tentado automaticamente";
        record(name, id, line, "");
        alert("&6" + name + " &7— " + line);
        if (online == null) {
            getLogger().warning("[revisão] " + name + " (" + id + "): " + line);
        }
    }

    // ------------------------------------------------------------------ registros

    private void review(Player p, String line, String evidence) {
        String name = p == null ? "?" : p.getName();
        UUID id = p == null ? null : p.getUniqueId();
        record(name, id, "[revisão] " + line, evidence);
        alert("&6" + name + " &7— " + line);
    }

    private void record(String name, UUID id, String line, String evidence) {
        if (!settings.auditLog()) {
            return;
        }
        audit.write(name + " (" + id + ") — " + line + (evidence == null || evidence.isEmpty() ? ""
                : " — evidências: " + evidence) + " — modo " + settings.mode());
    }

    private void alert(String legacy) {
        if (!settings.notifyStaff()) {
            return;
        }
        Component msg = text("&8[&6ZVB&8] " + legacy);
        mainThread(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("zwovulcanbridge.alerts")) {
                    p.sendMessage(msg);
                }
            }
        });
    }

    private void mainThread(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, r);
        } catch (IllegalPluginAccessException | IllegalStateException e) {
            counters.scheduleFailures.increment();
        }
    }

    // ------------------------------------------------------------------ sessões e proteções

    private void openSession(Player p, long now) {
        BridgeSettings st = settings;
        BedrockDetector.Result kind = bedrock.classify(p.getUniqueId(), p.getName(), st.bedrockNamePrefixes(),
                st.assumeJavaWithoutFloodgate());
        long sessionId;
        do {
            sessionId = ThreadLocalRandom.current().nextLong();
        } while (sessionId == 0L);
        sessions.put(p.getUniqueId(), new PlayerSession(p.getUniqueId(), p.getName(), sessionId, kind.kind(),
                kind.detail(), now));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        openSession(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        PlayerSession s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && s.ownsKickEvent()) {
            s.kickEventResult(!event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        PlayerSession s = sessions.remove(id);
        // Só um kick da ponte que passou pelo PlayerKickEvent pode contar. Saída voluntária, timeout
        // ou kick cancelado nunca contam.
        if (s == null || !Boolean.TRUE.equals(s.kickAccepted())) {
            return;
        }
        if (event.getReason() == PlayerQuitEvent.QuitReason.KICKED) {
            confirmKick(s);
        } else {
            counters.kicksUnconfirmed.increment();
            record(s.name(), id, "[revisão] kick " + s.kickNumber() + " não confirmado (saída: " + event.getReason()
                    + "): não contou", "");
        }
    }

    private void confirmKick(PlayerSession s) {
        long now = System.currentTimeMillis();
        BridgeSettings st = settings;
        OffenderRecord rec = history.get(s.playerId()).filter(r -> !r.expired(now, st.validityMillis()))
                .orElse(OffenderRecord.fresh(s.playerId(), s.name()));
        if (rec.lastSessionId() == s.sessionId() && rec.kicks() > 0) {
            return; // já contado nesta sessão
        }
        OffenderRecord next = rec.withKick(s.sessionId(), now, s.name());
        history.put(next);
        counters.kicksConfirmed.increment();
        record(s.name(), s.playerId(), "kick " + s.kickNumber() + " CONFIRMADO (desconexão pela ponte); kicks no "
                + "histórico: " + next.kicks() + "; válido até " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm")
                .format(new java.util.Date(next.firstKickAt() + st.validityMillis())), "");
        alert("&c" + s.name() + " &7— kick " + s.kickNumber() + " confirmado pelo anticheat.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        grace(event.getPlayer(), settings.teleportGraceMillis(), "teleporte");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        grace(event.getPlayer(), settings.respawnGraceMillis(), "respawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        grace(event.getPlayer(), settings.worldChangeGraceMillis(), "troca de mundo");
    }

    private void grace(Player p, long millis, String cause) {
        if (millis <= 0) {
            return;
        }
        PlayerSession s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.startGrace(System.currentTimeMillis() + millis, cause);
        }
    }

    // ------------------------------------------------------------------ acesso interno

    BridgeSettings settings() { return settings; }
    Escalation escalation() { return escalation; }
    ProviderTracker provider() { return provider; }
    VulcanHook vulcan() { return vulcan; }
    BedrockDetector bedrock() { return bedrock; }
    BanLink banLink() { return banLink; }
    HistoryStore history() { return history; }
    IoWorker io() { return io; }
    AuditLog audit() { return audit; }
    Map<UUID, PlayerSession> sessions() { return sessions; }
    VulcanConfigScanner.Report vulcanConfig() { return vulcanConfig; }

    /** /zvb limpar: a staff zera o histórico de um UUID. */
    boolean clearHistory(UUID id, String by) {
        boolean removed = history.remove(id);
        if (removed) {
            record("?", id, "histórico zerado por " + by, "");
        }
        return removed;
    }

    static Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy.replace("\\n", "\n"));
    }
}
