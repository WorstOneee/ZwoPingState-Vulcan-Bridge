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
 * Integração com o Vulcan sem dependência de compilação: as classes da API do Vulcan são
 * resolvidas uma única vez (MethodHandles) quando a ponte liga. Se a API mudar, a ponte
 * apenas desativa o hook e avisa no console, em vez de quebrar em cada evento.
 *
 * <p>Quem pune é o Vulcan. Aqui só escutamos {@code VulcanFlagEvent} e {@code VulcanPunishEvent}
 * (os dois são canceláveis) e cancelamos quando o filtro manda. Testado com Vulcan 2.9.7.29.</p>
 */
final class VulcanHook implements Listener {

    private static final String FLAG_EVENT = "me.frep.vulcan.api.event.VulcanFlagEvent";
    private static final String PUNISH_EVENT = "me.frep.vulcan.api.event.VulcanPunishEvent";
    private static final String CHECK = "me.frep.vulcan.api.check.Check";

    /** O que fazer com a punição do Vulcan. */
    enum Action { PASS, CANCEL, CANCEL_AND_RESET_VL }

    /** Decide se cancela. Pode ser chamado fora da thread principal. */
    interface Sink {
        boolean onFlag(Player player);

        Action onPunish(Player player, FlagInfo check);
    }

    private final Plugin owner;
    private final Logger logger;
    private final Sink sink;

    private MethodHandle flagPlayer;   // (Event)Player
    private MethodHandle punishPlayer; // (Event)Player
    private MethodHandle punishCheck;  // (Event)Object
    private MethodHandle getName;      // (Object)String
    private MethodHandle getType;      // (Object)char
    private MethodHandle getCategory;  // (Object)String
    private MethodHandle getVl;        // (Object)int
    private MethodHandle getMaxVl;     // (Object)int
    private MethodHandle isPunishable; // (Object)boolean
    private MethodHandle setVl;        // (Object,int)void; pode faltar em outras versões

    private boolean active;
    private boolean punishHooked;
    private String version = "-";

    private final LongAdder extractionErrors = new LongAdder();
    private final LongAdder vlResets = new LongAdder();

    VulcanHook(Plugin owner, Logger logger, Sink sink) {
        this.owner = owner;
        this.logger = logger;
        this.sink = sink;
    }

    /** Registra os listeners. Retorna false (e loga o motivo) se o Vulcan não estiver disponível. */
    boolean hook(PluginManager pm) {
        Plugin vulcan = pm.getPlugin("Vulcan");
        if (vulcan == null || !vulcan.isEnabled()) {
            logger.warning("Vulcan não encontrado ou desligado: a ponte não tem o que filtrar.");
            return false;
        }
        ClassLoader cl = vulcan.getClass().getClassLoader();
        try {
            Class<? extends Event> flagClass = event(FLAG_EVENT, cl);
            Class<? extends Event> punishClass = event(PUNISH_EVENT, cl);
            if (punishClass == null) {
                logger.severe("VulcanPunishEvent não encontrado: as punições do Vulcan NÃO podem ser filtradas.");
                return false;
            }
            Class<?> checkClass = Class.forName(CHECK, true, cl);
            MethodHandles.Lookup lk = MethodHandles.publicLookup();
            punishPlayer = player(lk, punishClass);
            punishCheck = lk.findVirtual(punishClass, "getCheck", MethodType.methodType(checkClass))
                    .asType(MethodType.methodType(Object.class, Event.class));
            getName = checkGetter(lk, checkClass, "getName", String.class);
            getType = checkGetter(lk, checkClass, "getType", char.class);
            getCategory = checkGetter(lk, checkClass, "getCategory", String.class);
            getVl = checkGetter(lk, checkClass, "getVl", int.class);
            getMaxVl = checkGetter(lk, checkClass, "getMaxVl", int.class);
            isPunishable = checkGetter(lk, checkClass, "isPunishable", boolean.class);
            try {
                setVl = lk.findVirtual(checkClass, "setVl", MethodType.methodType(void.class, int.class))
                        .asType(MethodType.methodType(void.class, Object.class, int.class));
            } catch (ReflectiveOperationException e) {
                setVl = null;
                logger.warning("Check#setVl não encontrado: o VL não será zerado após um bloqueio.");
            }

            // HIGH + ignoreCancelled: decide antes do MONITOR; o Vulcan lê isCancelled() depois.
            pm.registerEvent(punishClass, this, EventPriority.HIGH, (l, e) -> onPunish(e), owner, true);
            punishHooked = true;
            if (flagClass != null) {
                flagPlayer = player(lk, flagClass);
                pm.registerEvent(flagClass, this, EventPriority.HIGH, (l, e) -> onFlag(e), owner, true);
            } else {
                logger.warning("VulcanFlagEvent não encontrado: só as punições serão filtradas (o VL pode subir com lag).");
            }
            active = true;
            version = vulcan.getPluginMeta().getVersion();
            logger.info("Vulcan " + version + " conectado: filtrando punições"
                    + (flagClass != null ? " e flags." : "."));
            return true;
        } catch (Throwable t) {
            logger.severe("Falha ao integrar com o Vulcan (" + t + "). Nada será filtrado.");
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
        Player player;
        try {
            player = (Player) flagPlayer.invokeExact(event);
        } catch (Throwable t) {
            extractionErrors.increment();
            return;
        }
        if (player != null && sink.onFlag(player)) {
            ((Cancellable) event).setCancelled(true);
        }
    }

    private void onPunish(Event event) {
        Player player;
        FlagInfo info = null;
        Object check;
        try {
            player = (Player) punishPlayer.invokeExact(event);
            check = (Object) punishCheck.invokeExact(event);
            if (check != null) {
                info = new FlagInfo(
                        (String) getName.invokeExact(check),
                        (char) getType.invokeExact(check),
                        (String) getCategory.invokeExact(check),
                        (int) getVl.invokeExact(check),
                        (int) getMaxVl.invokeExact(check),
                        (boolean) isPunishable.invokeExact(check));
            }
        } catch (Throwable t) {
            // Sem conseguir ler o evento, não dá para provar que a conexão está boa: bloqueia.
            extractionErrors.increment();
            ((Cancellable) event).setCancelled(true);
            return;
        }
        if (player == null) {
            ((Cancellable) event).setCancelled(true);
            return;
        }
        Action action = sink.onPunish(player, info);
        if (action == Action.PASS) {
            return;
        }
        ((Cancellable) event).setCancelled(true);
        if (action == Action.CANCEL_AND_RESET_VL && check != null && setVl != null) {
            try {
                setVl.invokeExact(check, 0);
                vlResets.increment();
            } catch (Throwable t) {
                extractionErrors.increment();
            }
        }
    }

    private static MethodHandle player(MethodHandles.Lookup lk, Class<? extends Event> c)
            throws ReflectiveOperationException {
        return lk.findVirtual(c, "getPlayer", MethodType.methodType(Player.class))
                .asType(MethodType.methodType(Player.class, Event.class));
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
    boolean punishHooked() { return punishHooked; }
    String version() { return version; }
    long extractionErrors() { return extractionErrors.sum(); }
    long vlResets() { return vlResets.sum(); }
}
