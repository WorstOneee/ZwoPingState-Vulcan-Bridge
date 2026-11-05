package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.FlagEngine;
import br.com.serpens.vulcanbridge.core.FlagInfo;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import br.com.serpens.vulcanbridge.core.StrikeLedger;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZwoVulcanBridge: ponte entre o Vulcan e o SerpensModPanel.
 *
 * <p>Cada flag do Vulcan só é avaliada se o {@code PingStateProvider} disser STABLE para o
 * jogador. Qualquer outra resposta (ou provedor ausente) descarta a sequência de flags.
 * Por padrão a ponte está em modo OBSERVE: não pune ninguém.</p>
 */
public final class ZwoVulcanBridgePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private volatile BridgeSettings settings;
    private FlagEngine engine;
    private StrikeLedger ledger;
    private ProviderTracker provider;
    private VulcanHook vulcan;
    private BedrockDetector bedrock;
    private BridgeStorage storage;
    private OccurrenceHandler occurrences;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = BridgeSettings.load(getConfig());

        storage = new BridgeStorage(getDataFolder(), getLogger());
        engine = new FlagEngine(settings.policy());
        ledger = new StrikeLedger(settings.kicksBeforeBan(), settings.strikeExpireMillis());
        ledger.load(storage.loadStrikes());
        int purged = ledger.purgeExpired(System.currentTimeMillis());
        if (purged > 0) {
            storage.saveStrikesAsync(ledger);
        }

        provider = new ProviderTracker();
        provider.refresh();
        bedrock = BedrockDetector.create(getLogger());
        occurrences = new OccurrenceHandler(this);

        getServer().getPluginManager().registerEvents(provider, this);
        getServer().getPluginManager().registerEvents(this, this);

        vulcan = new VulcanHook(this, getLogger(), this::onVulcanFlag);
        vulcan.hook(getServer().getPluginManager(), settings.usePostFlagEvent(), settings.cancelVulcanPunishments());

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
        if (storage != null) {
            storage.shutdown();
            if (ledger != null) {
                storage.saveStrikesSync(ledger);
            }
        }
    }

    void reloadSettings() {
        reloadConfig();
        BridgeSettings previous = settings;
        BridgeSettings s = BridgeSettings.load(getConfig());
        settings = s;
        engine.policy(s.policy());
        ledger.configure(s.kicksBeforeBan(), s.strikeExpireMillis());
        vulcan.cancelPunishments(s.cancelVulcanPunishments());
        if (previous.usePostFlagEvent() != s.usePostFlagEvent() || !vulcan.active()) {
            vulcan.unhook();
            vulcan.hook(getServer().getPluginManager(), s.usePostFlagEvent(), s.cancelVulcanPunishments());
        }
        logStartup();
    }

    private void logStartup() {
        BridgeSettings s = settings;
        getLogger().info("Modo: " + s.mode() + (s.mode() == br.com.serpens.vulcanbridge.core.BridgeMode.OBSERVE
                ? " (observação: ninguém é punido)" : " (PUNIÇÕES ATIVAS)"));
        getLogger().info("Provedor de conexão: " + provider.describe());
        if (!s.banVerdict().allowed()) {
            getLogger().warning("Ban automático desativado: " + s.banVerdict().reason()
                    + ". A terceira ocorrência fica pendente para revisão manual.");
        }
    }

    // ------------------------------------------------------------------ fluxo de flags

    /** Chamado pelo hook do Vulcan, possivelmente fora da thread principal. */
    private void onVulcanFlag(Player player, FlagInfo flag) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return; // ainda não entrou de fato, ou já saiu
        }
        ConnectionStatus connection = provider.status(session.playerId());
        long now = System.currentTimeMillis();
        FlagEngine.Result r = engine.onFlag(session, flag, connection, now);
        if (r.outcome() == FlagEngine.Outcome.OCCURRENCE) {
            occurrences.onOccurrence(session, flag, connection);
        }
    }

    // ------------------------------------------------------------------ sessões e proteções

    private void openSession(Player p, long now) {
        boolean isBedrock = bedrock.isBedrock(p.getUniqueId(), p.getName(), settings.bedrockNamePrefixes());
        sessions.put(p.getUniqueId(),
                new PlayerSession(p.getUniqueId(), p.getName(), UUID.randomUUID().toString(), isBedrock, now));
    }

    @EventHandler(priority = EventPriority.MONITOR)
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
    FlagEngine engine() { return engine; }
    StrikeLedger ledger() { return ledger; }
    ProviderTracker provider() { return provider; }
    VulcanHook vulcan() { return vulcan; }
    BridgeStorage storage() { return storage; }
    Map<UUID, PlayerSession> sessions() { return sessions; }

    boolean isCurrentSession(UUID id, String sessionKey) {
        PlayerSession s = sessions.get(id);
        return s != null && s.sessionKey().equals(sessionKey);
    }

    static Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy.replace("\\n", "\n"));
    }
}
