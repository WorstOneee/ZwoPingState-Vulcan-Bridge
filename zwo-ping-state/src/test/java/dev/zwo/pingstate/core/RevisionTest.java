package dev.zwo.pingstate.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Revisões, sessão de coleta e epoch do snapshot (relógio simulado, 1 tick = 50 ms). */
class RevisionTest {

    private static final long TICK = 50_000_000L;

    private long now;
    private PingTracker tracker;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final AtomicInteger ping = new AtomicInteger(40);

    /** Leitura como a ponte faz: uma chamada, tudo junto. */
    record View(PingStatus status, long epoch, long session, long playerRev, long serverRev, long age,
                boolean fresh) {
    }

    @BeforeEach
    void setUp() {
        now = 1_000_000_000_000L;
        tracker = new PingTracker(PingSettings.defaults(), () -> now);
    }

    private void seconds(double s) {
        int n = (int) Math.round(s * 20);
        for (int i = 0; i < n; i++) {
            now += TICK;
            tracker.tick(now);
            tracker.tickEnd(now, 20.0);
        }
    }

    private View view(UUID id) {
        return tracker.view(id, now, View::new);
    }

    private void joinStable() {
        tracker.register(alice, "alice", false, ping::get, now);
        tracker.register(bob, "bob", false, () -> 45, now);
        seconds(10);
        assertEquals(PingState.STABLE, view(alice).status().state());
    }

    @Test
    @DisplayName("lag sem consulta no meio: a revisão avança e não volta com a recuperação")
    void revisionSurvivesRecovery() {
        joinStable();
        View before = view(alice);
        long stableRev = before.playerRev();
        long bobRev = view(bob).playerRev();
        seconds(5);
        assertEquals(stableRev, view(alice).playerRev(), "estável: revisão parada");

        ping.set(400); // lag, sem nenhuma flag/consulta
        seconds(3);
        ping.set(40);
        seconds(35); // recuperou por completo (janela de 20 s + recuperação de 10 s)
        View after = view(alice);
        assertEquals(PingState.STABLE, after.status().state());
        assertTrue(after.playerRev() > stableRev, "revisão não pode voltar: " + after.playerRev());
        assertEquals(before.session(), after.session());
        assertEquals(before.epoch(), after.epoch());
        assertEquals(bobRev, view(bob).playerRev(), "o lag da alice não mexe na revisão do bob");
    }

    @Test
    @DisplayName("revisão só cresce em toda a sequência de estados")
    void monotonic() {
        tracker.register(alice, "alice", false, ping::get, now);
        long last = view(alice).playerRev();
        int[] pings = {40, 40, 400, 40, 900, 40, 40, 40};
        for (int p : pings) {
            ping.set(p);
            for (int i = 0; i < 60; i++) {
                seconds(0.25);
                long r = view(alice).playerRev();
                assertTrue(r >= last);
                last = r;
            }
        }
    }

    @Test
    @DisplayName("travada global: revisão do servidor avança para todos, sem percorrer jogadores")
    void serverStallBumpsGlobalRevision() {
        joinStable();
        long a = view(alice).serverRev();
        long playerA = view(alice).playerRev();
        now += 600_000_000L; // um tick de 600 ms
        tracker.tick(now);
        seconds(15); // servidor recuperado
        View va = view(alice);
        View vb = view(bob);
        assertEquals(PingState.STABLE, va.status().state());
        assertTrue(va.serverRev() > a);
        assertEquals(va.serverRev(), vb.serverRev(), "mesma revisão global para todos");
        assertEquals(playerA, va.playerRev(), "a travada de 600 ms não é perda de continuidade do jogador");
    }

    @Test
    @DisplayName("UNKNOWN entre consultas (dado inconclusivo) avança a revisão")
    void unknownBumps() {
        AtomicInteger calls = new AtomicInteger();
        tracker.register(alice, "alice", false, () -> {
            if (calls.incrementAndGet() == 15) {
                throw new IllegalStateException("falha simulada"); // dado inconclusivo
            }
            return 40;
        }, now);
        seconds(14);
        View stable = view(alice);
        assertEquals(PingState.STABLE, stable.status().state());
        seconds(1); // 15ª leitura falha: UNKNOWN / falha na coleta
        assertEquals(PingState.UNKNOWN, view(alice).status().state());
        seconds(20); // volta a STABLE
        View back = view(alice);
        assertEquals(PingState.STABLE, back.status().state());
        assertTrue(back.playerRev() > stable.playerRev());
    }

    @Test
    @DisplayName("amostras vencidas: ao retomar a coleta a revisão já avançou (mesmo sem travada global)")
    void staleThenResume() {
        joinStable();
        PlayerEntry e = tracker.entry(alice);
        long r = view(alice).playerRev();
        long srv = view(alice).serverRev();
        now += 6_000_000_000L; // a coleta não leu a alice por 6 s
        View stale = view(alice);
        assertEquals(PingStatus.STALE, stale.status());
        assertTrue(!stale.fresh());
        tracker.sample(e, now, tracker.settings()); // retomada da coleta só deste jogador
        View resumed = view(alice);
        assertTrue(resumed.playerRev() > r, "retomada depois de vencer avança a revisão");
        assertEquals(srv, resumed.serverRev());
    }

    @Test
    @DisplayName("reconexão muda a sessão de coleta; novo provedor muda o epoch")
    void sessionAndEpoch() {
        joinStable();
        View first = view(alice);
        tracker.unregister(alice);
        assertEquals(0L, view(alice).session(), "não monitorado: sem sessão");
        assertTrue(!view(alice).fresh());
        tracker.register(alice, "alice", false, ping::get, now);
        seconds(10);
        View second = view(alice);
        assertNotEquals(first.session(), second.session());
        assertEquals(first.epoch(), second.epoch());

        PingTracker other = new PingTracker(PingSettings.defaults(), () -> now);
        assertNotEquals(tracker.epoch(), other.epoch(), "provedor recriado = outra identidade");
        assertNotEquals(0L, other.epoch());
    }

    @Test
    @DisplayName("estado e revisão saem do mesmo objeto publicado (nunca STABLE novo com revisão velha)")
    void stateAndRevisionPublishedTogether() {
        joinStable();
        PingSnapshot s1 = tracker.snapshot(alice);
        ping.set(400);
        seconds(2);
        ping.set(40);
        seconds(35);
        PingSnapshot s2 = tracker.snapshot(alice);
        assertEquals(PingState.STABLE, s2.status().state());
        assertTrue(s2.revision() > s1.revision());
        assertEquals(s2.revision(), view(alice).playerRev());
    }
}
