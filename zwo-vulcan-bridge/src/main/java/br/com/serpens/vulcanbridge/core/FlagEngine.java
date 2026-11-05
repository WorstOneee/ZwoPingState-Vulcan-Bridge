package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionStatus;

import java.util.concurrent.atomic.LongAdder;

/**
 * Decide o que fazer com cada flag do Vulcan. Sem Bukkit, sem I/O, sem log: só memória.
 *
 * <p>Ordem das regras:</p>
 * <ol>
 *   <li>Bedrock (se excluído) -&gt; ignorado, nunca entra na escada.</li>
 *   <li>Check ignorado / não punível -&gt; ignorado.</li>
 *   <li>Proteção de teleporte/respawn/troca de mundo -&gt; sequência descartada.</li>
 *   <li>Conexão diferente de STABLE (inclui provedor ausente) -&gt; sequência descartada.</li>
 *   <li>Já houve ocorrência válida nesta sessão -&gt; ignorado (no máximo uma por sessão).</li>
 *   <li>Flag abaixo do VL alto -&gt; não conta (mas também não zera a sequência).</li>
 *   <li>Flag alta -&gt; entra na sequência; ao atingir o mínimo dentro da janela, ocorrência.</li>
 * </ol>
 */
public final class FlagEngine {

    public enum Outcome {
        IGNORED_BEDROCK,
        IGNORED_CHECK,
        IGNORED_SESSION_DONE,
        DISCARDED_GRACE,
        DISCARDED_CONNECTION,
        LOW_VL,
        COUNTED,
        OCCURRENCE
    }

    /** Resultado de uma flag. {@code sequence} é o tamanho atual da sequência (0 se zerada). */
    public record Result(Outcome outcome, int sequence) {
        private static final Result[] SIMPLE = new Result[Outcome.values().length];

        static {
            for (Outcome o : Outcome.values()) {
                SIMPLE[o.ordinal()] = new Result(o, 0);
            }
        }

        static Result of(Outcome o) {
            return SIMPLE[o.ordinal()];
        }
    }

    private volatile FlagPolicy policy;

    private final LongAdder flagsSeen = new LongAdder();
    private final LongAdder sequencesDiscarded = new LongAdder();
    private final LongAdder flagsDiscardedConnection = new LongAdder();
    private final LongAdder flagsDiscardedGrace = new LongAdder();
    private final LongAdder occurrences = new LongAdder();

    public FlagEngine(FlagPolicy policy) {
        this.policy = policy;
    }

    public void policy(FlagPolicy policy) {
        this.policy = policy;
    }

    public FlagPolicy policy() {
        return policy;
    }

    public Result onFlag(PlayerSession session, FlagInfo flag, ConnectionStatus connection, long nowMillis) {
        FlagPolicy p = this.policy;
        flagsSeen.increment();

        if (session.bedrock() && p.excludeBedrock()) {
            return Result.of(Outcome.IGNORED_BEDROCK);
        }
        if (p.isIgnored(flag)) {
            return Result.of(Outcome.IGNORED_CHECK);
        }
        if (session.inGrace(nowMillis)) {
            flagsDiscardedGrace.increment();
            if (session.discard("proteção: " + session.graceCause(), nowMillis) > 0) {
                sequencesDiscarded.increment();
            }
            return Result.of(Outcome.DISCARDED_GRACE);
        }
        if (connection == null || !connection.allowsEvaluation()) {
            flagsDiscardedConnection.increment();
            if (session.discard("conexão: " + (connection == null ? "provedor indisponível"
                    : connection.reason().description()), nowMillis) > 0) {
                sequencesDiscarded.increment();
            }
            return Result.of(Outcome.DISCARDED_CONNECTION);
        }
        if (session.occurrenceThisSession()) {
            return Result.of(Outcome.IGNORED_SESSION_DONE);
        }
        if (flag.vl() < p.highVlThreshold(flag.maxVl())) {
            return Result.of(Outcome.LOW_VL);
        }
        int seq = session.addHighFlag(nowMillis, p.windowMillis(), p.requiredHighFlags());
        if (seq < 0) {
            occurrences.increment();
            return Result.of(Outcome.OCCURRENCE);
        }
        return new Result(Outcome.COUNTED, seq);
    }

    public long flagsSeen() { return flagsSeen.sum(); }
    public long sequencesDiscarded() { return sequencesDiscarded.sum(); }
    public long occurrences() { return occurrences.sum(); }
    public long flagsDiscardedConnection() { return flagsDiscardedConnection.sum(); }
    public long flagsDiscardedGrace() { return flagsDiscardedGrace.sum(); }
}
