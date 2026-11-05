package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.FlagInfo;
import br.com.serpens.vulcanbridge.core.Gate;
import br.com.serpens.vulcanbridge.core.GateReason;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZwoVulcanBridge: filtro de lag para as punições do Vulcan.
 *
 * <p><b>Quem pune é o Vulcan</b> (pelos {@code punishment-commands} do config dele). A ponte só
 * pergunta ao {@code PingStateProvider} (ZwoPingState) como está a conexão do jogador e cancela a
 * punição, e opcionalmente a flag, quando não está STABLE, quando o provedor falta, quando o
 * jogador é Bedrock ou está na proteção de teleporte/respawn. Por padrão (OBSERVE) bloqueia toda
 * punição e só registra o que teria passado.</p>
 */
public final class ZwoVulcanBridgePlugin extends JavaPlugin implements Listener, VulcanHook.Sink {

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private volatile BridgeSettings settings;
    private Gate gate;
    private ProviderTracker provider;
    private VulcanHook vulcan;
    private BedrockDetector bedrock;
    private AuditLog audit;
    private volatile List<String> vulcanIpBanCommands = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = BridgeSettings.load(getConfig());
        gate = new Gate(settings.policy());
        audit = new AuditLog(getDataFolder(), getLogger());

        provider = new ProviderTracker();
        provider.refresh();
        bedrock = BedrockDetector.create(getLogger());

        getServer().getPluginManager().registerEvents(provider, this);
        getServer().getPluginManager().registerEvents(this, this);

        vulcan = new VulcanHook(this, getLogger(), this);
        vulcan.hook(getServer().getPluginManager());

        // /reload ou ligar com jogadores online
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

        logStartup();
    }

    @Override
    public void onDisable() {
        if (vulcan != null) {
            vulcan.unhook();
        }
        sessions.clear();
        if (audit != null) {
            audit.shutdown();
        }
    }

    void reloadSettings() {
        reloadConfig();
        BridgeSettings s = BridgeSettings.load(getConfig());
        settings = s;
        gate.policy(s.policy());
        if (!vulcan.active()) {
            vulcan.hook(getServer().getPluginManager());
        }
        logStartup();
    }

    private void logStartup() {
        BridgeSettings s = settings;
        getLogger().info("Modo: " + s.mode() + (s.mode() == BridgeMode.OBSERVE
                ? " (observação: TODA punição do Vulcan é bloqueada e só registrada)"
                : " (o Vulcan pune quando a conexão está STABLE)"));
        getLogger().info("Provedor de conexão: " + provider.describe()
                + " (o ZwoPingState pode ligar logo depois desta mensagem)");
        vulcanIpBanCommands = s.checkVulcanCommands()
                ? VulcanCommandScanner.ipBanCommands(getServer().getPluginManager().getPlugin("Vulcan"))
                : List.of();
        if (!vulcanIpBanCommands.isEmpty()) {
            getLogger().warning("O config do Vulcan tem " + vulcanIpBanCommands.size()
                    + " comando(s) que podem banir IP: " + vulcanIpBanCommands
                    + ". A política do servidor é ban só por perfil: troque por /mpbanperfil %player% <motivo>.");
        }
    }

    // ------------------------------------------------------------------ filtro (Vulcan)

    /** Pode ser chamado fora da thread principal. O(1): mapa + leitura volátil do provedor. */
    @Override
    public boolean onFlag(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        ConnectionStatus c = provider.status(player.getUniqueId());
        return gate.onFlag(session, c, System.currentTimeMillis()).cancel();
    }

    /** No máximo uma linha de log/aviso por jogador+check+motivo nesse intervalo. */
    private static final long REPORT_INTERVAL_MILLIS = 30_000L;

    /** Raro (VL máximo atingido). Registra a decisão e avisa a staff. */
    @Override
    public VulcanHook.Action onPunish(Player player, FlagInfo check) {
        PlayerSession session = sessions.get(player.getUniqueId());
        ConnectionStatus c = provider.status(player.getUniqueId());
        long now = System.currentTimeMillis();
        Gate.Decision d = gate.onPunish(session, c, now);
        String key = (check == null ? "?" : check.key()) + "|" + d.reason();
        if (session == null || session.shouldReport(key, now, REPORT_INTERVAL_MILLIS)) {
            report(player, check, c, d);
        }
        if (!d.cancel()) {
            return VulcanHook.Action.PASS;
        }
        return settings.resetVlWhenBlocked() && gate.shouldResetVl(d)
                ? VulcanHook.Action.CANCEL_AND_RESET_VL : VulcanHook.Action.CANCEL;
    }

    private void report(Player player, FlagInfo check, ConnectionStatus c, Gate.Decision d) {
        BridgeSettings s = settings;
        String what = check == null ? "check desconhecido" : check.display();
        String verdict = !d.cancel() ? "LIBERADA" : d.reason() == GateReason.OBSERVE_MODE ? "BLOQUEADA (observação; teria passado)"
                : "BLOQUEADA (" + d.reason().description() + ")";
        String line = "Punição do Vulcan " + verdict + ": " + player.getName() + " (" + player.getUniqueId() + ") — "
                + what + " — conexão " + c.state() + "/" + c.reason() + " — modo " + s.mode();
        if (s.auditLog()) {
            audit.write(line);
        }
        if (s.notifyStaff()) {
            String color = !d.cancel() ? "&c" : d.reason() == GateReason.OBSERVE_MODE ? "&e" : "&a";
            Component msg = text("&8[&6ZVB&8] " + color + "Punição do Vulcan " + verdict + "&7: &f" + player.getName()
                    + " &7— " + what + " &8| &7conexão " + c.state());
            Runnable send = () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("zwovulcanbridge.alerts")) {
                        p.sendMessage(msg);
                    }
                }
            };
            if (Bukkit.isPrimaryThread()) {
                send.run();
            } else {
                Bukkit.getScheduler().runTask(this, send);
            }
        }
    }

    // ------------------------------------------------------------------ sessões e proteções

    private void openSession(Player p, long now) {
        boolean isBedrock = bedrock.isBedrock(p.getUniqueId(), p.getName(), settings.bedrockNamePrefixes());
        sessions.put(p.getUniqueId(), new PlayerSession(p.getUniqueId(), p.getName(), isBedrock, now));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        openSession(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
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
    Gate gate() { return gate; }
    ProviderTracker provider() { return provider; }
    VulcanHook vulcan() { return vulcan; }
    Map<UUID, PlayerSession> sessions() { return sessions; }
    List<String> vulcanIpBanCommands() { return vulcanIpBanCommands; }

    static Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy.replace("\\n", "\n"));
    }
}
