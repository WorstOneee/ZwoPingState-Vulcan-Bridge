package br.com.serpens.vulcanbridge.core;

import java.util.UUID;

/**
 * Estado de um jogador durante UMA sessão (do join ao quit). Liberado ao sair.
 *
 * <p>Os campos de sequência são protegidos por {@code synchronized(this)}, porque o Vulcan
 * pode disparar flags fora da thread principal.</p>
 */
public final class PlayerSession {

    private final UUID playerId;
    private final String name;
    private final String sessionKey;
    private final boolean bedrock;
    private final long joinedAtMillis;

    /** Até quando as flags são descartadas (teleporte, respawn, troca de mundo). */
    private volatile long graceUntilMillis;
    private volatile String graceCause = "";

    // --- protegido por this ---
    private final long[] highFlagTimes = new long[FlagPolicy.MAX_REQUIRED_FLAGS];
    private int highFlagCount;
    private boolean occurrenceThisSession;
    private long discardedSequences;
    private String lastDiscardReason = "";
    private long lastDiscardAtMillis;
    private String lastOccurrence = "";

    public PlayerSession(UUID playerId, String name, String sessionKey, boolean bedrock, long joinedAtMillis) {
        this.playerId = playerId;
        this.name = name;
        this.sessionKey = sessionKey;
        this.bedrock = bedrock;
        this.joinedAtMillis = joinedAtMillis;
    }

    public UUID playerId() { return playerId; }
    public String name() { return name; }
    public String sessionKey() { return sessionKey; }
    public boolean bedrock() { return bedrock; }
    public long joinedAtMillis() { return joinedAtMillis; }

    public void startGrace(long untilMillis, String cause) {
        if (untilMillis > graceUntilMillis) {
            graceUntilMillis = untilMillis;
            graceCause = cause;
        }
    }

    public boolean inGrace(long nowMillis) {
        return nowMillis < graceUntilMillis;
    }

    public long graceUntilMillis() { return graceUntilMillis; }
    public String graceCause() { return graceCause; }

    // ----------------------------------------------------------------- sequência

    /** Descarta a sequência. Retorna quantas flags altas foram descartadas. */
    synchronized int discard(String reason, long nowMillis) {
        int dropped = highFlagCount;
        if (dropped > 0) {
            discardedSequences++;
            lastDiscardReason = reason;
            lastDiscardAtMillis = nowMillis;
        }
        highFlagCount = 0;
        return dropped;
    }

    /**
     * Registra uma flag alta. Retorna o tamanho da sequência dentro da janela (após remover as
     * vencidas), ou -1 se esta flag confirmou a ocorrência (a sequência é zerada).
     */
    synchronized int addHighFlag(long nowMillis, long windowMillis, int required) {
        // remove flags fora da janela (mantém ordem cronológica)
        int keep = 0;
        long cutoff = nowMillis - windowMillis;
        for (int i = 0; i < highFlagCount; i++) {
            long t = highFlagTimes[i];
            if (t >= cutoff) {
                highFlagTimes[keep++] = t;
            }
        }
        highFlagCount = keep;
        if (highFlagCount < highFlagTimes.length) {
            highFlagTimes[highFlagCount++] = nowMillis;
        } else {
            System.arraycopy(highFlagTimes, 1, highFlagTimes, 0, highFlagTimes.length - 1);
            highFlagTimes[highFlagTimes.length - 1] = nowMillis;
        }
        if (highFlagCount >= required) {
            highFlagCount = 0;
            occurrenceThisSession = true;
            return -1;
        }
        return highFlagCount;
    }

    synchronized boolean occurrenceThisSession() { return occurrenceThisSession; }

    public synchronized void lastOccurrence(String text) { this.lastOccurrence = text; }

    /** Cópia consistente para o comando de diagnóstico. */
    public synchronized View view() {
        return new View(highFlagCount, occurrenceThisSession, discardedSequences, lastDiscardReason,
                lastDiscardAtMillis, lastOccurrence);
    }

    public record View(int sequence, boolean occurrenceThisSession, long discardedSequences,
                       String lastDiscardReason, long lastDiscardAtMillis, String lastOccurrence) {
    }
}
