package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.FlagInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Integração com o Vulcan sem dependência de compilação (MethodHandles resolvidos uma vez).
 *
 * <p>A evidência vem do {@code VulcanFlagEvent}, que o Vulcan 2.9.7.29 dispara também nos checks
 * com {@code punishable: false}. A ponte não altera nem cancela as flags.
 * O {@code VulcanPunishEvent} é sempre cancelado: os comandos automáticos do Vulcan devem ficar
 * desligados, e se algum check ainda estiver punível a ponte impede a punição e avisa.</p>
 */
final class VulcanHook implements Listener {

    private static final String FLAG_EVENT = "me.frep.vulcan.api.event.VulcanFlagEvent";
    private static final String PUNISH_EVENT = "me.frep.vulcan.api.event.VulcanPunishEvent";
    private static final String CHECK = "me.frep.vulcan.api.check.Check";

    interface Sink {
        /** Qualquer thread. Deve ser O(1) e sem I/O. */
        void onFlag(Player player, String checkName, char checkType, int vl);

        /** O Vulcan tentou punir (check punível). A punição já foi cancelada. */
        void onVulcanPunish(Player player, FlagInfo check);
    }

    private final Plugin owner;
    private final Logger logger;
    private final Sink sink;

    private MethodHandle flagPlayer;   // (Event)Player
    private MethodHandle flagCheck;    // (Event)Object
    private MethodHandle punishPlayer; // (Event)Player
    private MethodHandle punishCheck;  // (Event)Object
    private MethodHandle getName;      // (Object)String
    private MethodHandle getType;      // (Object)char
    private MethodHandle getCategory;  // (Object)String
    private MethodHandle getVl;        // (Object)int
    private MethodHandle getMaxVl;     // (Object)int
    private MethodHandle isPunishable; // (Object)boolean

    private volatile boolean active;
    private volatile boolean flagHooked;
    private volatile String version = "-";
    private volatile String failure = "";

    private final LongAdder flags = new LongAdder();
    private final LongAdder punishCancelled = new LongAdder();
    private final LongAdder extractionErrors = new LongAdder();

    VulcanHook(Plugin owner, Logger logger, Sink sink) {
        this.owner = owner;
        this.logger = logger;
        this.sink = sink;
    }

    boolean hook(PluginManager pm) {
        Plugin vulcan = pm.getPlugin("Vulcan");
        if (vulcan == null || !vulcan.isEnabled()) {
            failure = "Vulcan não encontrado ou desligado";
            logger.warning(failure + ": a ponte não recebe evidências.");
            return false;
        }
        ClassLoader cl = vulcan.getClass().getClassLoader();
        try {
            Class<? extends Event> flagClass = event(FLAG_EVENT, cl);
            Class<? extends Event> punishClass = event(PUNISH_EVENT, cl);
            if (flagClass == null) {
                failure = "VulcanFlagEvent não encontrado nesta versão do Vulcan";
                logger.severe(failure + ": a automação fica desligada.");
                return false;
            }
            Class<?> checkClass = Class.forName(CHECK, true, cl);
            MethodHandles.Lookup lk = MethodHandles.publicLookup();
            flagPlayer = player(lk, flagClass);
            flagCheck = check(lk, flagClass, checkClass);
            getName = checkGetter(lk, checkClass, "getName", String.class);
            getType = checkGetter(lk, checkClass, "getType", char.class);
            getCategory = checkGetter(lk, checkClass, "getCategory", String.class);
            getVl = checkGetter(lk, checkClass, "getVl", int.class);
            getMaxVl = checkGetter(lk, checkClass, "getMaxVl", int.class);
            isPunishable = checkGetter(lk, checkClass, "isPunishable", boolean.class);

            // MONITOR + ignoreCancelled: só olha (não altera a flag); flag cancelada por outro plugin não conta.
            pm.registerEvent(flagClass, this, EventPriority.MONITOR, (l, e) -> onFlag(e), owner, true);
            if (punishClass != null) {
                punishPlayer = player(lk, punishClass);
                punishCheck = check(lk, punishClass, checkClass);
                pm.registerEvent(punishClass, this, EventPriority.HIGHEST, (l, e) -> onPunish(e), owner, false);
            } else {
                logger.warning("VulcanPunishEvent não encontrado: a ponte não consegue impedir punições do próprio "
                        + "Vulcan. Deixe todos os checks com punishable: false.");
            }
            flagHooked = true;
            active = true;
            failure = "";
            version = vulcan.getPluginMeta().getVersion();
            logger.info("Vulcan " + version + " conectado: lendo flags (VulcanFlagEvent)"
                    + (punishClass != null ? " e cancelando as punições automáticas do Vulcan." : "."));
            return true;
        } catch (Throwable t) {
            failure = "falha ao integrar: " + t;
            logger.severe("Falha ao integrar com o Vulcan (" + t + "). A automação fica desligada.");
            HandlerList.unregisterAll(this);
            active = false;
            return false;
        }
    }

    void unhook() {
        HandlerList.unregisterAll(this);
        active = false;
    }

    private void onFlag(Event event) {
        flags.increment();
        Player player;
        String name;
        char type;
        int vl;
        try {
            player = (Player) flagPlayer.invokeExact(event);
            Object check = (Object) flagCheck.invokeExact(event);
            if (player == null || check == null) {
                return;
            }
            name = (String) getName.invokeExact(check);
            type = (char) getType.invokeExact(check);
            vl = (int) getVl.invokeExact(check);
        } catch (Throwable t) {
            extractionErrors.increment();
            return;
        }
        sink.onFlag(player, name, type, vl);
    }

    private void onPunish(Event event) {
        ((Cancellable) event).setCancelled(true);
        punishCancelled.increment();
        Player player;
        FlagInfo info = null;
        try {
            player = (Player) punishPlayer.invokeExact(event);
            Object check = (Object) punishCheck.invokeExact(event);
            if (check != null) {
                info = new FlagInfo((String) getName.invokeExact(check), (char) getType.invokeExact(check),
                        (String) getCategory.invokeExact(check), (int) getVl.invokeExact(check),
                        (int) getMaxVl.invokeExact(check), (boolean) isPunishable.invokeExact(check));
            }
        } catch (Throwable t) {
            extractionErrors.increment();
            return;
        }
        if (player != null) {
            sink.onVulcanPunish(player, info);
        }
    }

    private static MethodHandle player(MethodHandles.Lookup lk, Class<? extends Event> c)
            throws ReflectiveOperationException {
        return lk.findVirtual(c, "getPlayer", MethodType.methodType(Player.class))
                .asType(MethodType.methodType(Player.class, Event.class));
    }

    private static MethodHandle check(MethodHandles.Lookup lk, Class<? extends Event> c, Class<?> checkClass)
            throws ReflectiveOperationException {
        return lk.findVirtual(c, "getCheck", MethodType.methodType(checkClass))
                .asType(MethodType.methodType(Object.class, Event.class));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> event(String name, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(name, true, cl);
            return Event.class.isAssignableFrom(c) && Cancellable.class.isAssignableFrom(c)
                    ? (Class<? extends Event>) c : null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static MethodHandle checkGetter(MethodHandles.Lookup lk, Class<?> checkClass, String name, Class<?> ret)
            throws ReflectiveOperationException {
        return lk.findVirtual(checkClass, name, MethodType.methodType(ret))
                .asType(MethodType.methodType(ret, Object.class));
    }

    boolean active() { return active; }
    boolean flagHooked() { return flagHooked; }
    String version() { return version; }
    String failure() { return failure; }
    long flags() { return flags.sum(); }
    long punishCancelled() { return punishCancelled.sum(); }
    long extractionErrors() { return extractionErrors.sum(); }
}
