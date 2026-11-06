package dev.zwo.pingstate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Núcleo do ZwoPingState, sem Bukkit (testável).
 *
 * <ul>
 *   <li>{@link #tick(long)}: chamado uma vez por tick, na thread principal, por UMA única tarefa.
 *       Amostra um lote limitado de jogadores (rodízio), recalcula o estado deles e publica um
 *       snapshot imutável.</li>
 *   <li>{@link #query(UUID, long)}: O(1), qualquer thread. Um get no mapa, uma leitura volátil,
 *       comparação de idade e um teste da saúde global. Não percorre janela, não cria tarefas,
 *       não chama Bukkit.</li>
 * </ul>
 */
public final class PingTracker {

    private final ConcurrentHashMap<UUID, PlayerEntry> byId = new ConcurrentHashMap<>();
    private final ArrayList<PlayerEntry> rotation = new ArrayList<>(); // só thread principal
    private final ServerHealth health = new ServerHealth();
    private final CollectorStats stats = new CollectorStats();
    private final WindowStats scratch = new WindowStats();
    private final LongSupplier clock;
    /** Identidade desta instância do provedor: muda a cada criação (reinício, /reload, reinstalação). */
    private final long epoch;
    private long sessionCounter; // só thread principal

    private volatile PingSettings settings;
    private int cursor;
    private long tickCounter;

    /**
     * @param clock relógio em nanos para medir o custo/orçamento da coleta (System::nanoTime)
     */
    public PingTracker(PingSettings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        long r;
        do {
            r = ThreadLocalRandom.current().nextLong();
        } while (r == 0L);
        this.epoch = r;
    }

    /** Identidade desta instância (nunca 0). */
    public long epoch() {
        return epoch;
    }

    // ------------------------------------------------------------------ cadastro (thread principal)

    public void register(UUID id, String name, boolean bedrock, IntSupplier pingSource, long nowNanos) {
        unregister(id);
        PlayerEntry e = new PlayerEntry(id, name, bedrock, pingSource, settings.windowSize(), nowNanos,
                ++sessionCounter);
        if (bedrock && settings.bedrockUnknown()) {
            e.snapshot = new PingSnapshot(PingStatus.of(PingState.UNKNOWN, PingReason.BEDROCK_PROXY),
                    nowNanos, 0L, -1, -1, -1, -1, -1, 0, 0, 0L, e.revision);
        }
        e.index = rotation.size();
        rotation.add(e);
        byId.put(id, e);
    }

    public void unregister(UUID id) {
        PlayerEntry e = byId.remove(id);
        if (e == null) {
            return;
        }
        int last = rotation.size() - 1;
        PlayerEntry moved = rotation.remove(last);
        if (moved != e) {
            rotation.set(e.index, moved);
            moved.index = e.index;
        }
    }

    public void clear() {
        byId.clear();
        rotation.clear();
        cursor = 0;
    }

    /** Troca a configuração. Se a janela mudou de tamanho, as janelas são reiniciadas. */
    public void settings(PingSettings next) {
        PingSettings prev = this.settings;
        this.settings = next;
        if (prev.windowSize() != next.windowSize()) {
            for (PlayerEntry e : rotation) {
                e.window.resize(next.windowSize());
                e.goodSinceNanos = -1;
            }
        }
    }

    public PingSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ coleta (thread principal)

    /** Uma chamada por tick. Orçamento fixo: no máximo {@code maxSamplesPerTick} jogadores. */
    public void tick(long nowNanos) {
        long start = clock.getAsLong();
        PingSettings s = settings;
        int processed = 0;
        try {
            health.onTick(nowNanos, s);
            if (++tickCounter % s.sampleIntervalTicks() == 0) {
                health.refresh(nowNanos, s);
            }

            int n = rotation.size();
            if (n > 0) {
                int perTick = Math.min(s.maxSamplesPerTick(), ceilDiv(n, s.sampleIntervalTicks()));
                // Rodízio: examina poucos candidatos e só lê quem já completou o intervalo.
                // Em regime (n >= intervalo) todo candidato examinado está vencido.
                int maxExamined = Math.min(n, Math.max(8, perTick * 4));
                long deadline = start + s.maxCollectorNanosPerTick();
                for (int examined = 0; examined < maxExamined && processed < perTick; examined++) {
                    if (cursor >= rotation.size()) {
                        cursor = 0;
                    }
                    PlayerEntry e = rotation.get(cursor);
                    cursor++;
                    if (e.lastSampleTick != 0 && tickCounter - e.lastSampleTick < s.sampleIntervalTicks()) {
                        continue; // lido há menos de um intervalo
                    }
                    e.lastSampleTick = tickCounter;
                    sample(e, nowNanos, s);
                    processed++;
                    if ((processed & 7) == 0 && clock.getAsLong() > deadline) {
                        stats.budgetHit();
                        break;
                    }
                }
            }
        } finally {
            stats.record(clock.getAsLong() - start, processed);
        }
    }

    /**
     * Fim de tick (thread principal): repassa a duração do tick, em ms, para a saúde global. O(1);
     * não toca em jogadores.
     */
    public void tickEnd(long nowNanos, double durationMs) {
        health.onTickEnd(nowNanos, durationMs, settings);
    }

    void sample(PlayerEntry e, long now, PingSettings s) {
        if (e.bedrock && s.bedrockUnknown()) {
            e.lastSampleNanos = now;
            e.revision++;
            e.snapshot = new PingSnapshot(PingStatus.of(PingState.UNKNOWN, PingReason.BEDROCK_PROXY),
                    now, now, -1, -1, -1, -1, -1, 0, 0, 0L, e.revision);
            return;
        }
        int ping;
        try {
            ping = e.pingSource.getAsInt();
        } catch (Throwable t) {
            stats.error();
            e.window.clear();
            e.goodSinceNanos = -1;
            e.revision++;
            e.snapshot = new PingSnapshot(PingStatus.of(PingState.UNKNOWN, PingReason.COLLECTION_ERROR),
                    now, e.lastSampleNanos, -1, -1, -1, -1, -1, 0, 0, 0L, e.revision);
            return;
        }

        // Perda de continuidade: a última avaliação venceu antes desta amostra (coleta atrasada,
        // travada). Mesmo sem nenhuma consulta no meio, a revisão avança: evidências anteriores
        // não podem atravessar esse buraco.
        if (now - e.snapshot.evaluatedAtNanos() > s.staleNanos()) {
            e.revision++;
        }
        // Descontinuidade longa: recomeça a janela.
        if (e.lastSampleNanos != 0 && now - e.lastSampleNanos > s.resetGapNanos()) {
            e.window.clear();
            e.goodSinceNanos = -1;
        }
        e.lastSampleNanos = now;

        // Logo após o join o Paper informa 0 até o primeiro keep-alive: não é dado real.
        boolean valid = ping > 0 || (ping == 0 && now - e.joinedAtNanos >= s.zeroPingGraceNanos());
        if (valid) {
            e.window.add(ping);
        }
        evaluate(e, now, s);
    }

    private void evaluate(PlayerEntry e, long now, PingSettings s) {
        WindowStats w = scratch;
        e.window.compute(s.spikeJump(), s.spikeAbsolute(), w);

        if (w.samples < s.minSamples()) {
            e.goodSinceNanos = -1;
            publish(e, now, PingStatus.of(PingState.UNKNOWN, PingReason.INSUFFICIENT_DATA), w, 0L);
            return;
        }

        PingReason bad = null;
        if (w.last > s.maxPing() || w.average > s.maxAveragePing()) {
            bad = PingReason.HIGH_PING;
        } else if (w.spikes > s.maxSpikes()) {
            bad = PingReason.PING_SPIKES;
        } else if (w.jitter > s.maxJitter() || w.max - w.min > s.maxRange()) {
            // O getPing() do Paper 1.21.11 é a média dos keep-alives dos últimos 5 s: a
            // oscilação real aparece mais na amplitude da janela do que entre amostras.
            bad = PingReason.PING_JITTER;
        }

        if (bad != null) {
            e.goodSinceNanos = -1;
            e.hadInstability = true;
            publish(e, now, PingStatus.of(PingState.UNSTABLE, bad), w, s.recoveryNanos());
            return;
        }

        if (e.goodSinceNanos < 0) {
            e.goodSinceNanos = now;
        }
        if (e.hadInstability) {
            long goodFor = now - e.goodSinceNanos;
            if (goodFor < s.recoveryNanos()) {
                publish(e, now, PingStatus.of(PingState.UNSTABLE, PingReason.RECOVERING), w,
                        s.recoveryNanos() - goodFor);
                return;
            }
        }
        publish(e, now, PingStatus.of(PingState.STABLE, PingReason.OK), w, 0L);
    }

    private static void publish(PlayerEntry e, long now, PingStatus status, WindowStats w, long recoveryRemaining) {
        if (status.state() != PingState.STABLE) {
            e.revision++; // instabilidade ou dado inconclusivo: nunca volta
        }
        // Estado e revisão no mesmo objeto imutável, publicado numa única escrita volátil.
        e.snapshot = new PingSnapshot(status, now, e.lastSampleNanos, w.last, w.average, w.min, w.max,
                w.jitter, w.spikes, w.samples, recoveryRemaining, e.revision);
    }

    // ------------------------------------------------------------------ consulta (qualquer thread)

    /** O(1), sem alocação. Nunca retorna null. */
    public PingStatus query(UUID id, long nowNanos) {
        PlayerEntry e = byId.get(id);
        if (e == null) {
            return PingStatus.NOT_TRACKED;
        }
        PingSettings s = settings;
        PingSnapshot snap = e.snapshot;
        if (nowNanos - snap.evaluatedAtNanos() > s.staleNanos()) {
            return PingStatus.STALE;
        }
        PingStatus st = snap.status();
        if (st.state() == PingState.STABLE && health.unstable(nowNanos, s)) {
            return PingStatus.SERVER_UNSTABLE;
        }
        return st;
    }

    /** Recebe uma leitura consistente; evita alocação intermediária. */
    @FunctionalInterface
    public interface ViewFactory<T> {
        T make(PingStatus status, long epoch, long sessionId, long playerRevision, long serverRevision,
               long ageNanos, boolean fresh);
    }

    /**
     * O(1), qualquer thread. Uma leitura do snapshot do jogador (estado + revisão juntos) e uma da
     * marca global (revisão + último sinal ruim juntos).
     */
    public <T> T view(UUID id, long nowNanos, ViewFactory<T> f) {
        ServerHealth.Mark m = health.mark();
        PlayerEntry e = byId.get(id);
        if (e == null) {
            return f.make(PingStatus.NOT_TRACKED, epoch, 0L, 0L, m.revision(), -1L, false);
        }
        PingSettings s = settings;
        PingSnapshot snap = e.snapshot;
        long age = nowNanos - snap.evaluatedAtNanos();
        boolean fresh = age <= s.staleNanos();
        PingStatus st;
        if (!fresh) {
            st = PingStatus.STALE;
        } else {
            st = snap.status();
            if (st.state() == PingState.STABLE && m.unstable(nowNanos, s)) {
                st = PingStatus.SERVER_UNSTABLE;
            }
        }
        return f.make(st, epoch, e.sessionId, snap.revision(), m.revision(), age, fresh);
    }

    /** Para o comando de diagnóstico: o snapshot pronto (null se não monitorado). */
    public PingSnapshot snapshot(UUID id) {
        PlayerEntry e = byId.get(id);
        return e == null ? null : e.snapshot;
    }

    public PlayerEntry entry(UUID id) {
        return byId.get(id);
    }

    /** Cópia da lista de jogadores (só para o comando de diagnóstico, nunca no caminho quente). */
    public List<PlayerEntry> entries() {
        return new ArrayList<>(rotation);
    }

    public int size() {
        return byId.size();
    }

    public ServerHealth health() {
        return health;
    }

    public CollectorStats stats() {
        return stats;
    }

    /** Jogadores lidos por tick com a quantidade atual de jogadores. */
    public int currentBatchSize() {
        PingSettings s = settings;
        int n = rotation.size();
        return n == 0 ? 0 : Math.min(s.maxSamplesPerTick(), ceilDiv(n, s.sampleIntervalTicks()));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
