package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionSnapshot;

import java.util.concurrent.atomic.LongAdder;

/**
 * Junta as evidências a partir das flags do Vulcan. Não depende de Bukkit e pode ser chamado de
 * qualquer thread.
 *
 * <p>Custo por flag: um acesso ao mapa de regras (O(1)), leituras voláteis e, só para flags de
 * checks aprovados com VL suficiente, uma passagem pelo anel de evidências (tamanho fixo). Sem I/O,
 * memória fixa por sessão, sem alocação no caminho comum e sem agendar nada, exceto uma vez por ocorrência disparada.</p>
 */
public final class Escalation {

    public enum FlagResult {
        NO_SESSION,
        /** Provedor sem snapshot com revisões: nada conta (automação bloqueada). */
        PROVIDER_INCOMPATIBLE,
        /** Conexão não STABLE, vencida ou sem provedor: sequência descartada. */
        CONNECTION_DISCARD,
        /** A ocorrência desta sessão já foi disparada ou encerrada. */
        SESSION_DONE,
        /** Bedrock: fora da automação. */
        EXCLUDED,
        NOT_APPROVED,
        LOW_VL,
        /** Proteção de teleporte/respawn/troca de mundo ativa: flag não conta. */
        GRACE,
        EVIDENCE,
        /** A sequência anterior foi interrompida por instabilidade/troca de provedor: descartada; esta flag recomeça. */
        RESTARTED,
        /** Ocorrência disparada agora (no máximo uma vez por sessão). */
        TRIGGERED
    }

    private volatile EscalationPolicy policy;

    private final LongAdder flagsSeen = new LongAdder();
    private final LongAdder approvedFlags = new LongAdder();
    private final LongAdder connectionDiscards = new LongAdder();
    private final LongAdder graceIgnored = new LongAdder();
    private final LongAdder triggered = new LongAdder();
    private final LongAdder revisionDiscards = new LongAdder();
    private final LongAdder incompatible = new LongAdder();

    public Escalation(EscalationPolicy policy) {
        this.policy = policy;
    }

    public void policy(EscalationPolicy p) {
        this.policy = p;
    }

    public EscalationPolicy policy() {
        return policy;
    }

    /**
     * @param snap        snapshot consultado UMA vez para esta flag
     * @param providerGen geração do provedor na ponte (muda quando o provedor é trocado/removido)
     */
    public FlagResult onFlag(PlayerSession s, ConnectionSnapshot snap, long providerGen, String checkName,
                             char checkType, int vl, long now) {
        flagsSeen.increment();
        if (s == null) {
            return FlagResult.NO_SESSION;
        }
        if (snap == null || !snap.revisionsSupported()) {
            // Sem revisões não há como saber se houve lag entre as flags: nada conta.
            incompatible.increment();
            if (s.discard(snap == null ? null : snap.status(), "provedor sem revisões", now)) {
                connectionDiscards.increment();
            }
            return FlagResult.PROVIDER_INCOMPATIBLE;
        }
        if (!snap.usable()) {
            if (s.discard(snap.status(), snap.fresh() ? "conexão não STABLE" : "telemetria vencida", now)) {
                connectionDiscards.increment();
            }
            return FlagResult.CONNECTION_DISCARD;
        }
        if (s.stage() != PlayerSession.Stage.IDLE) {
            return FlagResult.SESSION_DONE;
        }
        if (s.kind() == AccountKind.BEDROCK) {
            return FlagResult.EXCLUDED;
        }
        EscalationPolicy p = policy;
        CheckRule rule = p.match(checkName, checkType);
        if (rule == null) {
            return FlagResult.NOT_APPROVED;
        }
        if (vl < rule.minVl()) {
            return FlagResult.LOW_VL;
        }
        if (s.inGrace(now)) {
            graceIgnored.increment();
            return FlagResult.GRACE;
        }
        approvedFlags.increment();
        return switch (s.offer(rule, vl, now, p, snap, providerGen)) {
            case TRIGGERED -> {
                triggered.increment();
                yield FlagResult.TRIGGERED;
            }
            case RESTARTED -> {
                revisionDiscards.increment();
                yield FlagResult.RESTARTED;
            }
            case ADDED -> FlagResult.EVIDENCE;
        };
    }

    /**
     * Revalidação imediatamente antes de agir, com um snapshot NOVO: {@code null} = pode agir; senão o
     * motivo do descarte. Não altera a sessão.
     */
    public static String revalidate(PlayerSession s, ConnectionSnapshot snap, long providerGen, long now) {
        if (snap == null || !snap.revisionsSupported()) {
            return "provedor sem snapshot com revisões";
        }
        if (!snap.fresh()) {
            return "telemetria vencida (" + snap.status().reason().description() + ")";
        }
        if (!snap.usable()) {
            return "conexão " + snap.state() + "/" + snap.status().reason();
        }
        String mismatch = s.anchorMismatch(snap, providerGen);
        if (mismatch != null) {
            return mismatch;
        }
        if (s.inGrace(now)) {
            return "proteção de " + s.graceCause();
        }
        return null;
    }

    public long flagsSeen() { return flagsSeen.sum(); }
    public long approvedFlags() { return approvedFlags.sum(); }
    public long connectionDiscards() { return connectionDiscards.sum(); }
    public long graceIgnored() { return graceIgnored.sum(); }
    public long triggered() { return triggered.sum(); }
    /** Sequências descartadas porque a revisão/sessão/provedor mudou entre flags. */
    public long revisionDiscards() { return revisionDiscards.sum(); }
    /** Flags recebidas com provedor sem revisões. */
    public long incompatibleFlags() { return incompatible.sum(); }
}
