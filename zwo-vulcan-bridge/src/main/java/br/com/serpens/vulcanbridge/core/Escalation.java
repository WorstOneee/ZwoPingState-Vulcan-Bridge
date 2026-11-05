package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;

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
        /** Conexão não STABLE: sequência descartada. */
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
        /** Ocorrência disparada agora (no máximo uma vez por sessão). */
        TRIGGERED
    }

    private volatile EscalationPolicy policy;

    private final LongAdder flagsSeen = new LongAdder();
    private final LongAdder approvedFlags = new LongAdder();
    private final LongAdder connectionDiscards = new LongAdder();
    private final LongAdder graceIgnored = new LongAdder();
    private final LongAdder triggered = new LongAdder();

    public Escalation(EscalationPolicy policy) {
        this.policy = policy;
    }

    public void policy(EscalationPolicy p) {
        this.policy = p;
    }

    public EscalationPolicy policy() {
        return policy;
    }

    public FlagResult onFlag(PlayerSession s, ConnectionStatus connection, String checkName, char checkType, int vl,
                             long now) {
        flagsSeen.increment();
        if (s == null) {
            return FlagResult.NO_SESSION;
        }
        if (connection == null || !connection.allowsEvaluation()) {
            if (s.discard(connection, "conexão não STABLE", now)) {
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
        return switch (s.offer(rule, vl, now, p)) {
            case TRIGGERED -> {
                triggered.increment();
                yield FlagResult.TRIGGERED;
            }
            case ADDED -> FlagResult.EVIDENCE;
        };
    }

    public long flagsSeen() { return flagsSeen.sum(); }
    public long approvedFlags() { return approvedFlags.sum(); }
    public long connectionDiscards() { return connectionDiscards.sum(); }
    public long graceIgnored() { return graceIgnored.sum(); }
    public long triggered() { return triggered.sum(); }
}
