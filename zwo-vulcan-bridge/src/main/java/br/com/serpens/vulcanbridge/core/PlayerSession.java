package br.com.serpens.vulcanbridge.core;

import java.util.UUID;

/**
 * Estado de um jogador durante UMA sessão (do join ao quit). Liberado ao sair.
 * Campos voláteis: o Vulcan pode disparar eventos fora da thread principal.
 */
public final class PlayerSession {

    private final UUID playerId;
    private final String name;
    private final boolean bedrock;
    private final long joinedAtMillis;

    /** Até quando flags/punições são barradas (teleporte, respawn, troca de mundo). */
    private volatile long graceUntilMillis;
    private volatile String graceCause = "";

    // diagnóstico (/zvb jogador); contagem aproximada é suficiente
    private volatile long flagsFiltered;
    private volatile GateReason lastFlagReason;
    private volatile long lastFlagAtMillis;
    /** Última flag com conexão UNSTABLE (lag de verdade); base da regra de lag recente. */
    private volatile long lastLagFlagAtMillis;
    private volatile long punishmentsBlocked;
    private volatile long punishmentsAllowed;
    private volatile GateReason lastPunishReason;
    private volatile long lastPunishAtMillis;
    private volatile String lastReportKey = "";
    private volatile long lastReportAtMillis;

    public PlayerSession(UUID playerId, String name, boolean bedrock, long joinedAtMillis) {
        this.playerId = playerId;
        this.name = name;
        this.bedrock = bedrock;
        this.joinedAtMillis = joinedAtMillis;
    }

    public UUID playerId() { return playerId; }
    public String name() { return name; }
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

    void flagFiltered(GateReason reason, long nowMillis) {
        flagsFiltered++;
        lastFlagReason = reason;
        lastFlagAtMillis = nowMillis;
        if (reason == GateReason.CONNECTION_UNSTABLE) {
            lastLagFlagAtMillis = nowMillis;
        }
    }

    void punishment(GateReason reason, long nowMillis) {
        if (reason == GateReason.ALLOWED) {
            punishmentsAllowed++;
        } else {
            punishmentsBlocked++;
        }
        lastPunishReason = reason;
        lastPunishAtMillis = nowMillis;
    }

    /**
     * Limita log/aviso a uma linha por check a cada {@code intervalMillis} (o Vulcan tenta punir
     * de novo a cada flag acima do VL máximo). A contagem continua sendo feita.
     */
    public boolean shouldReport(String key, long nowMillis, long intervalMillis) {
        if (key.equals(lastReportKey) && nowMillis - lastReportAtMillis < intervalMillis) {
            return false;
        }
        lastReportKey = key;
        lastReportAtMillis = nowMillis;
        return true;
    }

    public long flagsFiltered() { return flagsFiltered; }
    public GateReason lastFlagReason() { return lastFlagReason; }
    public long lastFlagAtMillis() { return lastFlagAtMillis; }
    public long lastLagFlagAtMillis() { return lastLagFlagAtMillis; }
    public long punishmentsBlocked() { return punishmentsBlocked; }
    public long punishmentsAllowed() { return punishmentsAllowed; }
    public GateReason lastPunishReason() { return lastPunishReason; }
    public long lastPunishAtMillis() { return lastPunishAtMillis; }
}
