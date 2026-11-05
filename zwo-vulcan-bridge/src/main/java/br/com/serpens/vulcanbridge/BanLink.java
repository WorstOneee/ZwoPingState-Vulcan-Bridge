package br.com.serpens.vulcanbridge;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;

/**
 * Acompanha a {@code ProfileBanApi} do ModPanel. Sem ela (ou com versão incompatível) a automação
 * fica bloqueada e o motivo aparece no /zvb status. Nunca simula sucesso com comandos.
 */
final class BanLink implements Listener {

    static final String API_CLASS = "dev.zwo.modpanel.api.ProfileBanApi";

    enum State { READY, MISSING, INCOMPATIBLE }

    private record Current(BanBackend backend, State state, String reason) {
    }

    private volatile Current current = new Current(null, State.MISSING, "ainda não verificado");

    void refresh() {
        Plugin mp = Bukkit.getPluginManager().getPlugin("ModPanel");
        if (mp == null || !mp.isEnabled()) {
            current = new Current(null, State.MISSING, "ModPanel não encontrado ou desligado");
            return;
        }
        try {
            Class.forName(API_CLASS, false, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            current = new Current(null, State.INCOMPATIBLE, "ModPanel " + mp.getPluginMeta().getVersion()
                    + " não tem a ProfileBanApi (precisa do ModPanel 3.1.0 ou mais novo)");
            return;
        }
        try {
            ModPanelBackend.Lookup l = ModPanelBackend.lookup();
            current = new Current(l.backend(), l.state(), l.reason());
        } catch (LinkageError e) {
            current = new Current(null, State.INCOMPATIBLE, "ProfileBanApi incompatível: " + e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(ServiceRegisterEvent event) {
        if (API_CLASS.equals(event.getProvider().getService().getName())) {
            refresh();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnregister(ServiceUnregisterEvent event) {
        if (API_CLASS.equals(event.getProvider().getService().getName())) {
            current = new Current(null, State.MISSING, "ProfileBanApi removida (ModPanel desligando?)");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if ("ModPanel".equals(event.getPlugin().getName())) {
            refresh();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if ("ModPanel".equals(event.getPlugin().getName())) {
            current = new Current(null, State.MISSING, "ModPanel desligado");
        }
    }

    State state() { return current.state(); }
    String reason() { return current.reason(); }

    /** {@code null} se não estiver pronto. */
    BanBackend backend() {
        Current c = current;
        return c.state() == State.READY ? c.backend() : null;
    }

    String describe() {
        Current c = current;
        return switch (c.state()) {
            case READY -> "pronto — " + c.backend().describe();
            case MISSING -> "AUSENTE — " + c.reason();
            case INCOMPATIBLE -> "INCOMPATÍVEL — " + c.reason();
        };
    }
}
