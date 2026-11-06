package dev.zwo.pingstate;

import dev.zwo.pingstate.core.PingSettings;
import dev.zwo.pingstate.core.PingTracker;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * ZwoPingState: informa, por UUID, se a conexão do jogador está STABLE, UNSTABLE ou UNKNOWN.
 * Não pune ninguém.
 *
 * <p>Custo: UMA tarefa síncrona por tick, com orçamento fixo de jogadores por execução.
 * Nenhum I/O, rede, banco ou log no caminho de coleta/consulta.</p>
 */
public final class ZwoPingStatePlugin extends JavaPlugin implements Listener {

    private PingTracker tracker;
    private BukkitTask task;
    private BedrockCheck bedrock;
    private volatile List<String> bedrockPrefixes = List.of();
    private boolean bridgeRegistered;
    private long lastErrorLogMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PingSettings settings = SettingsLoader.load(getConfig());
        bedrockPrefixes = SettingsLoader.bedrockPrefixes(getConfig());
        bedrock = BedrockCheck.create();

        tracker = new PingTracker(settings, System::nanoTime);

        getServer().getPluginManager().registerEvents(this, this);
        long now = System.nanoTime();
        for (Player p : Bukkit.getOnlinePlayers()) {
            track(p, now); // /reload: todos começam em UNKNOWN de novo
        }

        task = getServer().getScheduler().runTaskTimer(this, this::collect, 1L, 1L);

        PingStateCommand cmd = new PingStateCommand(this);
        PluginCommand pc = getCommand("pingstate");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        registerBridgeProvider();
        getLogger().info("Coleta: 1 amostra por jogador a cada " + settings.sampleIntervalTicks()
                + " ticks, até " + settings.maxSamplesPerTick() + " jogadores por tick. Floodgate: "
                + (bedrock.available() ? "API" : "heurística"));
    }

    private void registerBridgeProvider() {
        if (getServer().getPluginManager().getPlugin("ZwoVulcanBridge") == null) {
            getLogger().warning("ZwoVulcanBridge não instalada: o estado fica disponível só pelo /pingstate.");
            return;
        }
        try {
            boolean full = dev.zwo.pingstate.bridge.BridgeRegistration.register(this, tracker);
            bridgeRegistered = true;
            if (full) {
                getLogger().info("Provedor PingStateProvider registrado para a ZwoVulcanBridge (snapshot com revisões).");
            } else {
                getLogger().warning("ZwoVulcanBridge antiga (sem snapshot com revisões): provedor registrado só com o "
                        + "estado. Atualize a ponte para 3.1.0 ou mais nova.");
            }
        } catch (LinkageError e) {
            getLogger().log(Level.SEVERE, "Versão da ZwoVulcanBridge incompatível; provedor não registrado.", e);
        }
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        // ServicesManager.unregisterAll é feito pelo Paper ao desligar; deixamos explícito.
        getServer().getServicesManager().unregisterAll(this);
        if (tracker != null) {
            tracker.clear();
        }
    }

    private void collect() {
        try {
            tracker.tick(System.nanoTime());
        } catch (Throwable t) {
            // Nunca loga por tick: no máximo uma linha por minuto.
            long now = System.currentTimeMillis();
            if (now - lastErrorLogMillis > 60_000L) {
                lastErrorLogMillis = now;
                getLogger().log(Level.WARNING, "Falha na coleta (estados vencerão para UNKNOWN)", t);
            }
        }
    }

    private void track(Player p, long nowNanos) {
        boolean isBedrock = bedrock.isBedrock(p.getUniqueId(), p.getName(), bedrockPrefixes);
        tracker.register(p.getUniqueId(), p.getName(), isBedrock, p::getPing, nowNanos);
    }

    /**
     * Duração de cada tick, informada pelo próprio Paper. Só soma numa janela fixa (O(1)); evita
     * chamar Server#getAverageTickTime(), que recalcula estatísticas a cada chamada.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        try {
            tracker.tickEnd(System.nanoTime(), event.getTickDuration());
        } catch (Throwable ignored) {
            // nunca derruba o tick; a coleta continua e a consulta usa o que tiver
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer(), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.unregister(event.getPlayer().getUniqueId());
    }

    void reloadSettings() {
        reloadConfig();
        tracker.settings(SettingsLoader.load(getConfig()));
        bedrockPrefixes = SettingsLoader.bedrockPrefixes(getConfig());
    }

    PingTracker tracker() {
        return tracker;
    }

    boolean bridgeRegistered() {
        return bridgeRegistered;
    }
}
