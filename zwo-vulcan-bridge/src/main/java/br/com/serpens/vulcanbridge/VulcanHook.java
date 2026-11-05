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
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Integração com o Vulcan sem dependência de compilação: as classes da API do Vulcan são
 * resolvidas uma única vez (MethodHandles) quando a ponte liga. Se a API mudar, a ponte
 * apenas desativa o hook e avisa no console, em vez de quebrar em cada flag.
 *
 * <p>Testado contra Vulcan 2.9.7.x (me.frep.vulcan.api).</p>
 */
final class VulcanHook implements Listener {

    private static final String FLAG_EVENT = "me.frep.vulcan.api.event.VulcanFlagEvent";
    private static final String POST_FLAG_EVENT = "me.frep.vulcan.api.event.VulcanPostFlagEvent";
    private static final String PUNISH_EVENT = "me.frep.vulcan.api.event.VulcanPunishEvent";
    private static final String CHECK = "me.frep.vulcan.api.check.Check";

    private final Plugin owner;
    private final Logger logger;
    private final BiConsumer<Player, FlagInfo> flagSink;

    private MethodHandle getPlayer;   // (Event)Player
    private MethodHandle getCheck;    // (Event)Object
    private MethodHandle getName;     // (Object)String
    private MethodHandle getType;     // (Object)char
    private MethodHandle getCategory; // (Object)String
    private MethodHandle getVl;       // (Object)int
    private MethodHandle getMaxVl;    // (Object)int
    private MethodHandle isPunishable;// (Object)boolean

    private boolean active;
    private String eventName = "-";
    private int vlOffset;
    private volatile boolean cancelPunishments;

    private final LongAdder flagsReceived = new LongAdder();
    private final LongAdder punishCancelled = new LongAdder();
    private final LongAdder extractionErrors = new LongAdder();

    VulcanHook(Plugin owner, Logger logger, BiConsumer<Player, FlagInfo> flagSink) {
        this.owner = owner;
        this.logger = logger;
        this.flagSink = flagSink;
    }

    /** Registra os listeners. Retorna false (e loga o motivo) se o Vulcan não estiver disponível. */
    boolean hook(PluginManager pm, boolean preferPostFlag, boolean cancelVulcanPunishments) {
        this.cancelPunishments = cancelVulcanPunishments;
        Plugin vulcan = pm.getPlugin("Vulcan");
        if (vulcan == null || !vulcan.isEnabled()) {
            logger.warning("Vulcan não encontrado ou desligado: nenhuma flag será avaliada.");
            return false;
        }
        ClassLoader cl = vulcan.getClass().getClassLoader();
        try {
            Class<? extends Event> flagClass = null;
            if (preferPostFlag) {
                flagClass = tryEvent(POST_FLAG_EVENT, cl);
                vlOffset = 0;
                eventName = "VulcanPostFlagEvent";
            }
            if (flagClass == null) {
                flagClass = tryEvent(FLAG_EVENT, cl);
                // No VulcanFlagEvent o VL ainda não foi incrementado por esta flag.
                vlOffset = 1;
                eventName = "VulcanFlagEvent";
            }
            if (flagClass == null) {
                logger.severe("Nenhum evento de flag do Vulcan encontrado. Versão do Vulcan incompatível?");
                return false;
            }
            Class<?> checkClass = Class.forName(CHECK, true, cl);
            MethodHandles.Lookup lk = MethodHandles.publicLookup();
            getPlayer = lk.findVirtual(flagClass, "getPlayer", MethodType.methodType(Player.class))
                    .asType(MethodType.methodType(Player.class, Event.class));
            getCheck = lk.findVirtual(flagClass, "getCheck", MethodType.methodType(checkClass))
                    .asType(MethodType.methodType(Object.class, Event.class));
            getName = checkGetter(lk, checkClass, "getName", String.class);
            getType = checkGetter(lk, checkClass, "getType", char.class);
            getCategory = checkGetter(lk, checkClass, "getCategory", String.class);
            getVl = checkGetter(lk, checkClass, "getVl", int.class);
            getMaxVl = checkGetter(lk, checkClass, "getMaxVl", int.class);
            isPunishable = checkGetter(lk, checkClass, "isPunishable", boolean.class);

            // MONITOR + ignoreCancelled: só vemos flags que o Vulcan (ou outro plugin) não cancelou.
            pm.registerEvent(flagClass, this, EventPriority.MONITOR, (l, e) -> onFlag(e), owner, true);

            Class<? extends Event> punishClass = tryEvent(PUNISH_EVENT, cl);
            if (punishClass != null) {
                pm.registerEvent(punishClass, this, EventPriority.LOWEST, (l, e) -> onVulcanPunish(e), owner, false);
            } else {
                logger.warning("VulcanPunishEvent não encontrado: punições internas do Vulcan não podem ser bloqueadas.");
            }
            active = true;
            logger.info("Vulcan " + vulcan.getPluginMeta().getVersion() + " conectado via " + eventName + ".");
            return true;
        } catch (Throwable t) {
            logger.severe("Falha ao integrar com o Vulcan (" + t + "). Nenhuma flag será avaliada.");
            HandlerList.unregisterAll(this);
            active = false;
            return false;
        }
    }

    void unhook() {
        HandlerList.unregisterAll(this);
        active = false;
    }

    void cancelPunishments(boolean value) {
        this.cancelPunishments = value;
    }

    private void onFlag(Event event) {
        flagsReceived.increment();
        FlagInfo info;
        Player player;
        try {
            player = (Player) getPlayer.invokeExact(event);
            Object check = (Object) getCheck.invokeExact(event);
            if (player == null || check == null) {
                return;
            }
            info = new FlagInfo(
                    (String) getName.invokeExact(check),
                    (char) getType.invokeExact(check),
                    (String) getCategory.invokeExact(check),
                    (int) getVl.invokeExact(check) + vlOffset,
                    (int) getMaxVl.invokeExact(check),
                    (boolean) isPunishable.invokeExact(check));
        } catch (Throwable t) {
            extractionErrors.increment();
            return;
        }
        flagSink.accept(player, info);
    }

    private void onVulcanPunish(Event event) {
        if (cancelPunishments && event instanceof Cancellable c) {
            c.setCancelled(true);
            punishCancelled.increment();
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> tryEvent(String name, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(name, true, cl);
            return Event.class.isAssignableFrom(c) ? (Class<? extends Event>) c : null;
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
    String eventName() { return eventName; }
    long flagsReceived() { return flagsReceived.sum(); }
    long punishCancelled() { return punishCancelled.sum(); }
    long extractionErrors() { return extractionErrors.sum(); }
}
