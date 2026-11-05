package br.com.serpens.vulcanbridge.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Histórico de ocorrências por perfil (UUID) e a escada de punições:
 * kick, kick (em sessões distintas) e, somente na terceira ocorrência válida, ban por perfil.
 *
 * <p>Thread-safe (métodos sincronizados). Só é tocado quando há uma ocorrência confirmada,
 * o que é raro; nunca no caminho de cada flag.</p>
 */
public final class StrikeLedger {

    public enum Step { KICK, BAN }

    /** Próximo passo da escada e quantos kicks válidos já existem. */
    public record Decision(Step step, int previousKicks) {
    }

    private final Map<UUID, List<Strike>> strikes = new HashMap<>();
    private int kicksBeforeBan;
    private long expireMillis;
    private long version;

    public StrikeLedger(int kicksBeforeBan, long expireMillis) {
        configure(kicksBeforeBan, expireMillis);
    }

    public synchronized void configure(int kicksBeforeBan, long expireMillis) {
        this.kicksBeforeBan = Math.max(1, kicksBeforeBan);
        this.expireMillis = expireMillis <= 0 ? Long.MAX_VALUE : expireMillis;
    }

    /** Quantos kicks válidos (sessões distintas, não vencidos, no modo informado). */
    public synchronized int validKicks(UUID id, BridgeMode mode, long nowMillis) {
        List<Strike> list = strikes.get(id);
        if (list == null) {
            return 0;
        }
        Set<String> sessions = new HashSet<>();
        for (Strike s : list) {
            if (s.mode() == mode && s.action() == Strike.Action.KICK && !expired(s, nowMillis)) {
                sessions.add(s.sessionKey());
            }
        }
        return sessions.size();
    }

    /**
     * Decide o próximo passo para uma ocorrência na sessão {@code sessionKey}.
     * Um kick da mesma sessão nunca conta duas vezes.
     */
    public synchronized Decision decide(UUID id, BridgeMode mode, String sessionKey, long nowMillis) {
        List<Strike> list = strikes.get(id);
        Set<String> kickSessions = new HashSet<>();
        if (list != null) {
            for (Strike s : list) {
                if (s.mode() == mode && s.action() == Strike.Action.KICK && !expired(s, nowMillis)) {
                    kickSessions.add(s.sessionKey());
                }
            }
        }
        // a terceira ocorrência só é válida se acontecer numa sessão diferente das dos kicks
        boolean sameSessionAsKick = kickSessions.contains(sessionKey);
        int kicks = kickSessions.size();
        if (kicks >= kicksBeforeBan && !sameSessionAsKick) {
            return new Decision(Step.BAN, kicks);
        }
        return new Decision(Step.KICK, kicks);
    }

    public synchronized void record(UUID id, Strike strike) {
        strikes.computeIfAbsent(id, k -> new ArrayList<>()).add(strike);
        version++;
    }

    public synchronized List<Strike> history(UUID id) {
        List<Strike> list = strikes.get(id);
        return list == null ? List.of() : List.copyOf(list);
    }

    public synchronized boolean clear(UUID id) {
        boolean removed = strikes.remove(id) != null;
        if (removed) {
            version++;
        }
        return removed;
    }

    /** Remove strikes vencidos. Retorna quantos foram removidos. */
    public synchronized int purgeExpired(long nowMillis) {
        int removed = 0;
        var it = strikes.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int before = e.getValue().size();
            e.getValue().removeIf(s -> expired(s, nowMillis));
            removed += before - e.getValue().size();
            if (e.getValue().isEmpty()) {
                it.remove();
            }
        }
        if (removed > 0) {
            version++;
        }
        return removed;
    }

    /** Cópia para gravação assíncrona. */
    public synchronized Map<UUID, List<Strike>> snapshot() {
        Map<UUID, List<Strike>> copy = new HashMap<>();
        strikes.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    public synchronized void load(Map<UUID, List<Strike>> data) {
        strikes.clear();
        data.forEach((k, v) -> strikes.put(k, new ArrayList<>(v)));
        version++;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized int size() {
        return strikes.size();
    }

    private boolean expired(Strike s, long nowMillis) {
        return expireMillis != Long.MAX_VALUE && nowMillis - s.atMillis() > expireMillis;
    }
}
