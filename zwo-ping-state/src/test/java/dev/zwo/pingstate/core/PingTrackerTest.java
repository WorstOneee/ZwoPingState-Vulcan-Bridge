package dev.zwo.pingstate.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenários de decisão do ZwoPingState com relógio simulado (1 tick = 50 ms).
 * Configuração padrão: 1 amostra/s, janela 20, mínimo 8, recuperação 10 s, vence em 5 s.
 */
class PingTrackerTest {

    private static final long TICK = 50_000_000L;

    private long now;
    private double mspt;
    private PingTracker tracker;
    private final UUID alice = UUID.randomUUID();
    private final AtomicInteger alicePing = new AtomicInteger(40);

    @BeforeEach
    void setUp() {
        now = 1_000_000_000_000L;
        mspt = 20.0;
        tracker = new PingTracker(PingSettings.defaults(), () -> now);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            now += TICK;
            tracker.tick(now);
            tracker.tickEnd(now, mspt);
        }
    }

    private void seconds(double s) {
        ticks((int) Math.round(s * 20));
    }

    private PingStatus alice() {
        return tracker.query(alice, now);
    }

    private void joinAlice() {
        tracker.register(alice, "alice", false, alicePing::get, now);
    }

    @Test
    @DisplayName("entrada sem histórico: UNKNOWN até ter amostras suficientes, depois STABLE")
    void joinWithoutHistory() {
        joinAlice();
        assertEquals(PingStatus.of(PingState.UNKNOWN, PingReason.INSUFFICIENT_DATA), alice());
        seconds(5);
        assertEquals(PingState.UNKNOWN, alice().state(), "5 amostras < mínimo 8");
        seconds(4);
        assertEquals(PingStatus.of(PingState.STABLE, PingReason.OK), alice());
    }

    @Test
    @DisplayName("jogador não cadastrado: UNKNOWN / NOT_TRACKED")
    void notTracked() {
        assertEquals(PingStatus.NOT_TRACKED, tracker.query(UUID.randomUUID(), now));
    }

    @Test
    @DisplayName("ping 0 logo após entrar não conta como amostra")
    void zeroPingAfterJoinIgnored() {
        alicePing.set(0);
        joinAlice();
        seconds(15);
        assertEquals(PingReason.INSUFFICIENT_DATA, alice().reason());
        alicePing.set(35);
        seconds(9);
        assertEquals(PingState.STABLE, alice().state());
    }

    @Test
    @DisplayName("ping alto e constante: UNSTABLE / HIGH_PING")
    void constantHighPing() {
        alicePing.set(320);
        joinAlice();
        seconds(10);
        assertEquals(PingStatus.of(PingState.UNSTABLE, PingReason.HIGH_PING), alice());
        seconds(60);
        assertEquals(PingReason.HIGH_PING, alice().reason(), "continua instável enquanto o ping estiver alto");
    }

    @Test
    @DisplayName("pico de ping: UNSTABLE, depois recuperação contínua antes de voltar a STABLE")
    void spikeThenRecovery() {
        joinAlice();
        seconds(10);
        assertEquals(PingState.STABLE, alice().state());

        alicePing.set(180); // salto de 140 ms
        seconds(1);
        assertEquals(PingStatus.of(PingState.UNSTABLE, PingReason.PING_SPIKES), alice());
        alicePing.set(40);

        // o pico (subida e descida) fica na janela por ~20 s
        seconds(15);
        assertEquals(PingReason.PING_SPIKES, alice().reason());
        seconds(7);
        assertEquals(PingStatus.of(PingState.UNSTABLE, PingReason.RECOVERING), alice(),
                "janela limpa, mas ainda sem 10 s contínuos bons");
        seconds(11);
        assertEquals(PingState.STABLE, alice().state());
    }

    @Test
    @DisplayName("uma única amostra boa não libera a avaliação")
    void singleGoodSampleDoesNotRelease() {
        alicePing.set(300);
        joinAlice();
        seconds(10);
        assertEquals(PingReason.HIGH_PING, alice().reason());
        // ping volta ao normal: precisa a média baixar E 10 s contínuos bons
        alicePing.set(40);
        seconds(1);
        assertEquals(PingState.UNSTABLE, alice().state());
        seconds(12); // média ainda alta (amostras antigas de 300 na janela) ou em recuperação
        assertEquals(PingState.UNSTABLE, alice().state());
        seconds(30);
        assertEquals(PingState.STABLE, alice().state());
    }

    @Test
    @DisplayName("ping oscilando: UNSTABLE / PING_JITTER")
    void jitter() {
        joinAlice();
        // alterna 40/110: salto de 70 (abaixo do pico de 80), jitter médio 70 > 40
        for (int i = 0; i < 12; i++) {
            alicePing.set(i % 2 == 0 ? 40 : 110);
            seconds(1);
        }
        assertEquals(PingStatus.of(PingState.UNSTABLE, PingReason.PING_JITTER), alice());
    }

    @Test
    @DisplayName("oscilação lenta (média de 5 s do Paper): amplitude alta = PING_JITTER")
    void slowOscillationByRange() {
        joinAlice();
        int[] wave = {60, 75, 90, 105, 120, 135, 120, 105, 90, 75}; // passos de 15 ms, amplitude 75
        for (int i = 0; i < 14; i++) {
            alicePing.set(wave[i % wave.length]);
            seconds(1);
        }
        assertEquals(PingStatus.of(PingState.UNSTABLE, PingReason.PING_JITTER), alice());
    }

    @Test
    @DisplayName("queda de TPS: STABLE vira UNSTABLE / SERVER_UNSTABLE e exige recuperação do servidor")
    void tpsDrop() {
        joinAlice();
        seconds(10);
        assertEquals(PingState.STABLE, alice().state());

        // 3 s com ticks de 80 ms (~12,5 TPS), sem travada (> 250 ms)
        for (int i = 0; i < 40; i++) {
            now += 80_000_000L;
            tracker.tick(now);
        }
        assertEquals(PingStatus.SERVER_UNSTABLE, alice());
        assertTrue(tracker.health().shortTps() < 18.0);
        seconds(5);
        assertEquals(PingStatus.SERVER_UNSTABLE, alice(), "recuperação do servidor: 10 s");
        seconds(7);
        assertEquals(PingState.STABLE, alice().state());
    }

    @Test
    @DisplayName("MSPT alto: servidor instável")
    void highMspt() {
        joinAlice();
        seconds(10);
        mspt = 65.0;
        seconds(1);
        assertEquals(PingStatus.SERVER_UNSTABLE, alice());
        assertEquals(ServerHealth.Cause.HIGH_MSPT, tracker.health().lastCause());
    }

    @Test
    @DisplayName("travada isolada: um tick longo marca instabilidade global sem percorrer jogadores")
    void isolatedStall() {
        joinAlice();
        seconds(10);
        now += 600_000_000L; // um tick de 600 ms
        tracker.tick(now);
        assertEquals(PingStatus.SERVER_UNSTABLE, alice());
        assertEquals(ServerHealth.Cause.STALL, tracker.health().lastCause());
        seconds(11);
        assertEquals(PingState.STABLE, alice().state());
    }

    @Test
    @DisplayName("amostras vencidas: sem coleta por mais de 5 s a consulta responde UNKNOWN / STALE_DATA")
    void staleSamples() {
        joinAlice();
        seconds(10);
        assertEquals(PingState.STABLE, alice().state());
        now += 6_000_000_000L; // servidor parado / lote atrasado: nenhuma coleta
        assertEquals(PingStatus.STALE, alice());
    }

    @Test
    @DisplayName("travada longa: depois dela a janela recomeça (UNKNOWN), sem reaproveitar dados velhos")
    void longGapResetsWindow() {
        joinAlice();
        seconds(10);
        now += 7_000_000_000L;
        tracker.tick(now);
        seconds(2);
        assertEquals(PingState.UNKNOWN, alice().state());
        assertEquals(PingReason.INSUFFICIENT_DATA, tracker.snapshot(alice).status().reason());
    }

    @Test
    @DisplayName("reconexão: começa de novo em UNKNOWN, sem herdar a janela anterior")
    void reconnect() {
        joinAlice();
        seconds(10);
        assertEquals(PingState.STABLE, alice().state());
        tracker.unregister(alice);
        assertEquals(PingStatus.NOT_TRACKED, alice());
        joinAlice();
        assertEquals(PingReason.INSUFFICIENT_DATA, alice().reason());
        seconds(3);
        assertEquals(PingState.UNKNOWN, alice().state());
    }

    @Test
    @DisplayName("Bedrock: UNKNOWN / BEDROCK_PROXY")
    void bedrock() {
        UUID geyser = new UUID(0L, 12345L);
        tracker.register(geyser, ".celular", true, () -> 5, now);
        seconds(10);
        assertEquals(PingStatus.of(PingState.UNKNOWN, PingReason.BEDROCK_PROXY), tracker.query(geyser, now));
    }

    @Test
    @DisplayName("falha ao ler o ping: UNKNOWN / COLLECTION_ERROR")
    void collectionError() {
        tracker.register(alice, "alice", false, () -> {
            throw new IllegalStateException("conexão fechada");
        }, now);
        seconds(2);
        assertEquals(PingStatus.of(PingState.UNKNOWN, PingReason.COLLECTION_ERROR), alice());
        assertTrue(tracker.stats().errors() > 0);
    }

    @Test
    @DisplayName("orçamento: com 100 jogadores cada tick lê no máximo 5 e todos são lidos 1x por segundo")
    void batchingBudget() {
        List<AtomicInteger> reads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            AtomicInteger counter = new AtomicInteger();
            reads.add(counter);
            tracker.register(UUID.randomUUID(), "p" + i, false, () -> {
                counter.incrementAndGet();
                return 50;
            }, now);
        }
        assertEquals(5, tracker.currentBatchSize());
        long before = tracker.stats().totalSamples();
        ticks(1);
        assertEquals(5, tracker.stats().totalSamples() - before, "lote de um tick");
        seconds(10);
        for (AtomicInteger c : reads) {
            assertTrue(c.get() >= 10 && c.get() <= 11, "cada jogador ~1 leitura/s, lido " + c.get());
        }
    }

    @Test
    @DisplayName("orçamento fixo: com muitos jogadores o lote é limitado a max-players-per-tick")
    void batchingCap() {
        for (int i = 0; i < 1000; i++) {
            tracker.register(UUID.randomUUID(), "p" + i, false, () -> 50, now);
        }
        assertEquals(32, tracker.currentBatchSize());
        long before = tracker.stats().totalSamples();
        ticks(1);
        assertEquals(32, tracker.stats().totalSamples() - before);
    }

    @Test
    @DisplayName("saída no meio do rodízio: os demais continuam sendo amostrados")
    void quitDuringRotation() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            tracker.register(id, "p" + i, false, () -> 50, now);
        }
        seconds(3);
        for (int i = 0; i < 40; i += 3) {
            tracker.unregister(ids.get(i));
        }
        seconds(10);
        for (int i = 0; i < 40; i++) {
            PingStatus st = tracker.query(ids.get(i), now);
            if (i % 3 == 0) {
                assertEquals(PingStatus.NOT_TRACKED, st);
            } else {
                assertEquals(PingState.STABLE, st.state(), "jogador " + i);
            }
        }
    }
}
