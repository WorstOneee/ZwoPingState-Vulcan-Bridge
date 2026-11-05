package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;

import java.util.concurrent.atomic.LongAdder;

/**
 * Filtro da ponte, sem Bukkit (testável). Quem pune é o Vulcan; aqui só se decide se a flag ou a
 * punição do Vulcan passa ou é cancelada.
 *
 * <p>Custo por chamada: alguns testes de campo, nenhuma alocação além do resultado em cache,
 * nenhum I/O. Pode ser chamado de qualquer thread (o Vulcan dispara eventos fora da thread
 * principal).</p>
 */
public final class Gate {

    /** Resultado. {@code cancel} = a ponte deve cancelar o evento do Vulcan. */
    public record Decision(boolean cancel, GateReason reason) {
        static final Decision PASS = new Decision(false, GateReason.ALLOWED);
    }

    private static final Decision[] CANCEL = new Decision[GateReason.values().length];
    private static final Decision[] KEEP = new Decision[GateReason.values().length];

    static {
        for (GateReason r : GateReason.values()) {
            CANCEL[r.ordinal()] = new Decision(true, r);
            KEEP[r.ordinal()] = new Decision(false, r);
        }
    }

    private volatile Policy policy;

    // contadores (leitura no /zvb status)
    private final LongAdder flagsSeen = new LongAdder();
    private final LongAdder flagsCancelled = new LongAdder();
    private final LongAdder flagsWouldCancel = new LongAdder();
    private final LongAdder punishSeen = new LongAdder();
    private final LongAdder punishAllowed = new LongAdder();
    private final LongAdder[] punishBlocked = new LongAdder[GateReason.values().length];

    {
        for (int i = 0; i < punishBlocked.length; i++) {
            punishBlocked[i] = new LongAdder();
        }
    }

    /**
     * @param mode            OBSERVE ou ENFORCE
     * @param cancelFlags     ENFORCE: cancela também a FLAG com lag. Atenção: o Vulcan deixa de
     *                        dar setback nessas flags (provável; quem forja lag andaria livre). Padrão: false.
     * @param excludeBedrock  Bedrock nunca é punido automaticamente
     * @param recentLagMillis com flags mantidas: se houve flag com conexão UNSTABLE há menos que isso,
     *                        a punição é bloqueada (o VL foi inflado por lag). 0 = desliga.
     */
    public record Policy(BridgeMode mode, boolean cancelFlags, boolean excludeBedrock, long recentLagMillis) {
        public static Policy defaults() {
            return new Policy(BridgeMode.OBSERVE, false, true, 120_000L);
        }
    }

    public Gate(Policy policy) {
        this.policy = policy;
    }

    public void policy(Policy next) {
        this.policy = next;
    }

    public Policy policy() {
        return policy;
    }

    /** Motivo de bloqueio pela conexão/proteção, ou null se a conexão permite punir. */
    static GateReason blockReason(PlayerSession session, ConnectionStatus connection, long nowMillis) {
        if (session == null) {
            return GateReason.NO_SESSION;
        }
        if (session.inGrace(nowMillis)) {
            return GateReason.PROTECTION;
        }
        if (connection == null) {
            return GateReason.PROVIDER_UNAVAILABLE;
        }
        if (connection.state() == ConnectionState.STABLE) {
            return null;
        }
        if (connection.reason() == ConnectionReason.PROVIDER_UNAVAILABLE) {
            return GateReason.PROVIDER_UNAVAILABLE;
        }
        return connection.state() == ConnectionState.UNSTABLE
                ? GateReason.CONNECTION_UNSTABLE : GateReason.CONNECTION_UNKNOWN;
    }

    /**
     * Flag do Vulcan. Em ENFORCE, cancela a flag quando a conexão não permite punir, para o VL
     * não acumular por lag. Em OBSERVE nunca cancela (só conta o que teria cancelado).
     * Bedrock: a flag segue normal (alerta para a staff); quem barra é {@link #onPunish}.
     */
    public Decision onFlag(PlayerSession session, ConnectionStatus connection, long nowMillis) {
        flagsSeen.increment();
        Policy p = policy;
        if (session != null && session.bedrock() && p.excludeBedrock()) {
            return KEEP[GateReason.BEDROCK.ordinal()];
        }
        GateReason block = blockReason(session, connection, nowMillis);
        if (block == null) {
            return Decision.PASS;
        }
        if (session != null) {
            session.flagFiltered(block, nowMillis);
        }
        if (p.mode() == BridgeMode.ENFORCE && p.cancelFlags()) {
            flagsCancelled.increment();
            return CANCEL[block.ordinal()];
        }
        flagsWouldCancel.increment();
        return KEEP[block.ordinal()];
    }

    /**
     * Punição do Vulcan (VL máximo atingido). Bedrock, proteção ou conexão diferente de STABLE:
     * cancela. Conexão STABLE: passa em ENFORCE; em OBSERVE é cancelada como "teria passado".
     */
    public Decision onPunish(PlayerSession session, ConnectionStatus connection, long nowMillis) {
        punishSeen.increment();
        Policy p = policy;
        GateReason reason;
        if (session != null && session.bedrock() && p.excludeBedrock()) {
            reason = GateReason.BEDROCK;
        } else {
            reason = blockReason(session, connection, nowMillis);
            // Só lag de verdade (UNSTABLE) contamina o VL. UNKNOWN logo após entrar (sem amostras)
            // bloqueia a punição na hora, mas não conta aqui: entrar no servidor não é lag.
            if (reason == null && !p.cancelFlags() && p.recentLagMillis() > 0 && session.lastLagFlagAtMillis() != 0
                    && nowMillis - session.lastLagFlagAtMillis() < p.recentLagMillis()) {
                reason = GateReason.RECENT_LAG;
            }
        }
        if (reason == null) {
            if (p.mode() == BridgeMode.ENFORCE) {
                punishAllowed.increment();
                if (session != null) {
                    session.punishment(GateReason.ALLOWED, nowMillis);
                }
                return Decision.PASS;
            }
            reason = GateReason.OBSERVE_MODE;
        }
        punishBlocked[reason.ordinal()].increment();
        if (session != null) {
            session.punishment(reason, nowMillis);
        }
        return CANCEL[reason.ordinal()];
    }

    /**
     * Zerar o VL do check depois de bloquear? Só em ENFORCE e só quando o bloqueio é por lag,
     * proteção ou Bedrock: o VL não representa trapaça provada e, sem zerar, o Vulcan tentaria
     * punir de novo a cada flag. Em OBSERVE o Vulcan não é alterado.
     */
    public boolean shouldResetVl(Decision d) {
        return d.cancel() && policy.mode() == BridgeMode.ENFORCE && d.reason() != GateReason.OBSERVE_MODE
                && d.reason() != GateReason.NO_SESSION;
    }

    public long flagsSeen() { return flagsSeen.sum(); }
    public long flagsCancelled() { return flagsCancelled.sum(); }
    public long flagsWouldCancel() { return flagsWouldCancel.sum(); }
    public long punishSeen() { return punishSeen.sum(); }
    public long punishAllowed() { return punishAllowed.sum(); }
    public long punishBlocked(GateReason reason) { return punishBlocked[reason.ordinal()].sum(); }

    public long punishBlockedTotal() {
        long sum = 0;
        for (LongAdder a : punishBlocked) {
            sum += a.sum();
        }
        return sum;
    }
}
