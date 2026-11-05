package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado de um jogador durante UMA sessão (do join ao quit). Liberado ao sair.
 *
 * <p>As flags do Vulcan podem chegar fora da thread principal: as evidências ficam sob o monitor
 * desta instância e o estágio é um {@link AtomicReference} (só uma ocorrência por sessão).</p>
 */
public final class PlayerSession {

    /** Estágio da ocorrência desta sessão. */
    public enum Stage {
        /** Juntando evidências. */
        IDLE,
        /** Ocorrência disparada; ação agendada na thread principal. */
        TRIGGERED,
        /** Esta sessão já teve a sua ocorrência (aplicada, registrada ou recusada). */
        DONE
    }

    private final UUID playerId;
    private final String name;
    private final long sessionId;
    private final AccountKind kind;
    private final String kindDetail;
    private final long joinedAtMillis;

    private volatile long graceUntilMillis;
    private volatile String graceCause = "";

    private final EvidenceBuffer evidence;
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.IDLE);
    private volatile String triggerSummary = "";
    private volatile long triggeredAtMillis;

    // diagnóstico (/zvb jogador): contagem aproximada basta
    private volatile long approvedFlags;
    private volatile long discards;
    private volatile ConnectionStatus lastDiscardStatus;
    private volatile String lastDiscardCause = "";
    private volatile long lastDiscardAtMillis;
    private volatile String lastDecision = "";

    // Só na thread principal: confirmação do kick aplicado pela ponte.
    private boolean kickCall;
    private Boolean kickAccepted;
    private int kickNumber;

    public PlayerSession(UUID playerId, String name, long sessionId, AccountKind kind, String kindDetail,
                         long joinedAtMillis) {
        this.playerId = playerId;
        this.name = name;
        this.sessionId = sessionId;
        this.kind = kind;
        this.kindDetail = kindDetail;
        this.joinedAtMillis = joinedAtMillis;
        this.evidence = new EvidenceBuffer();
    }

    public UUID playerId() { return playerId; }
    public String name() { return name; }
    public long sessionId() { return sessionId; }
    public AccountKind kind() { return kind; }
    public String kindDetail() { return kindDetail; }
    public long joinedAtMillis() { return joinedAtMillis; }

    // ------------------------------------------------------------------ proteções

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

    // ------------------------------------------------------------------ evidências

    /** Resultado de {@link #offer}. */
    enum Offer { ADDED, TRIGGERED }

    synchronized Offer offer(CheckRule rule, int vl, long now, EscalationPolicy policy) {
        if (stage.get() != Stage.IDLE) {
            return Offer.ADDED;
        }
        approvedFlags++;
        evidence.add(rule, vl, now, policy);
        if (evidence.satisfiedFamilies(policy, now) >= policy.minFamilies()
                && stage.compareAndSet(Stage.IDLE, Stage.TRIGGERED)) {
            triggerSummary = evidence.summary(policy, now);
            triggeredAtMillis = now;
            evidence.clear();
            return Offer.TRIGGERED;
        }
        return Offer.ADDED;
    }

    /** Descarta a sequência em andamento. Devolve {@code true} se havia evidência. */
    public synchronized boolean discard(ConnectionStatus status, String cause, long now) {
        if (evidence.size() == 0) {
            return false;
        }
        evidence.clear();
        markDiscard(status, cause, now);
        return true;
    }

    private void markDiscard(ConnectionStatus status, String cause, long now) {
        discards++;
        lastDiscardStatus = status;
        lastDiscardCause = cause;
        lastDiscardAtMillis = now;
    }

    public synchronized int evidenceCount() {
        return evidence.size();
    }

    // ------------------------------------------------------------------ estágio

    public Stage stage() {
        return stage.get();
    }

    /** Volta a juntar evidências do zero (a ação foi cancelada antes de acontecer). */
    public synchronized void resetToIdle() {
        evidence.clear();
        stage.compareAndSet(Stage.TRIGGERED, Stage.IDLE);
    }

    /** A ocorrência desta sessão terminou: nenhuma outra nesta sessão. */
    public void finish(String decision) {
        stage.set(Stage.DONE);
        lastDecision = decision;
    }

    public void decision(String decision) {
        lastDecision = decision;
    }

    public String triggerSummary() { return triggerSummary; }
    public long triggeredAtMillis() { return triggeredAtMillis; }

    // ------------------------------------------------------------------ kick (thread principal)

    public void beginKick(int number) {
        kickCall = true;
        kickAccepted = null;
        kickNumber = number;
    }

    public void endKickCall() {
        kickCall = false;
    }

    /** Chamado pelo listener de PlayerKickEvent enquanto o kick da ponte está em andamento. */
    public boolean ownsKickEvent() {
        return kickCall;
    }

    public void kickEventResult(boolean accepted) {
        kickAccepted = accepted;
    }

    /** {@code null} = nenhum kick da ponte nesta sessão. */
    public Boolean kickAccepted() { return kickAccepted; }
    public int kickNumber() { return kickNumber; }

    // ------------------------------------------------------------------ diagnóstico

    public long approvedFlags() { return approvedFlags; }
    public long discards() { return discards; }
    public ConnectionStatus lastDiscardStatus() { return lastDiscardStatus; }
    public String lastDiscardCause() { return lastDiscardCause; }
    public long lastDiscardAtMillis() { return lastDiscardAtMillis; }
    public String lastDecision() { return lastDecision; }
}
