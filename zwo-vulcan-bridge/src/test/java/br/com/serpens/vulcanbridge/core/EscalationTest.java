package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.core.Escalation.FlagResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscalationTest {

    static final ConnectionStatus STABLE = ConnectionStatus.of(ConnectionState.STABLE, ConnectionReason.OK);
    static final ConnectionStatus UNSTABLE = ConnectionStatus.of(ConnectionState.UNSTABLE, ConnectionReason.HIGH_PING);
    static final ConnectionStatus NO_PROVIDER =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.PROVIDER_UNAVAILABLE);

    EscalationPolicy policy;
    Escalation esc;
    PlayerSession s;
    long t;

    @BeforeEach
    void setUp() {
        policy = EscalationPolicy.of(List.of(
                new EscalationPolicy.Spec("speed", "movimento", 10, 2),
                new EscalationPolicy.Spec("fly", "movimento", 8, 2),
                new EscalationPolicy.Spec("killaura:a", "combate", 5, 3)), 60_000, 2);
        esc = new Escalation(policy);
        s = session(AccountKind.JAVA);
        t = 1_000_000L;
    }

    PlayerSession session(AccountKind kind) {
        return new PlayerSession(UUID.randomUUID(), "x", 42L, kind, "", 0L);
    }

    FlagResult flag(String check, char type, int vl) {
        t += 1000;
        return esc.onFlag(s, STABLE, check, type, vl, t);
    }

    /** Duas famílias completas: speed x2 + killaura:a x3. */
    FlagResult fullSequence() {
        flag("speed", 'a', 12);
        flag("speed", 'b', 15);
        flag("killaura", 'a', 6);
        flag("killaura", 'a', 6);
        return flag("killaura", 'a', 7);
    }

    @Test
    void twoFamiliesTrigger() {
        assertEquals(FlagResult.TRIGGERED, fullSequence());
        assertEquals(PlayerSession.Stage.TRIGGERED, s.stage());
        assertTrue(s.triggerSummary().contains("speed"));
        assertTrue(s.triggerSummary().contains("killaura:a"));
    }

    @Test
    void singleFlagNeverTriggers() {
        assertEquals(FlagResult.EVIDENCE, flag("speed", 'a', 50));
        assertEquals(PlayerSession.Stage.IDLE, s.stage());
    }

    @Test
    void manyFlagsOfSameCheckNeverTrigger() {
        for (int i = 0; i < 15; i++) {
            assertEquals(FlagResult.EVIDENCE, flag("speed", 'a', 30));
        }
        assertEquals(PlayerSession.Stage.IDLE, s.stage());
    }

    @Test
    void sameFamilyDifferentChecksNeverTrigger() {
        for (int i = 0; i < 5; i++) {
            flag("speed", 'a', 30);
            flag("fly", 'a', 30);
        }
        assertEquals(PlayerSession.Stage.IDLE, s.stage(), "speed e fly são da mesma família");
    }

    @Test
    void lowVlAndUnapprovedChecksDoNotCount() {
        assertEquals(FlagResult.LOW_VL, flag("speed", 'a', 9));
        assertEquals(FlagResult.NOT_APPROVED, flag("killaura", 'b', 50), "só killaura:a foi aprovado");
        assertEquals(FlagResult.NOT_APPROVED, flag("timer", 'a', 50));
        assertEquals(0, s.evidenceCount());
    }

    @Test
    void repetitionsRequiredPerCheck() {
        flag("speed", 'a', 12);
        flag("killaura", 'a', 6);
        flag("killaura", 'a', 6);
        flag("killaura", 'a', 6);
        assertEquals(PlayerSession.Stage.IDLE, s.stage(), "speed só 1x (min-flags 2)");
        assertEquals(FlagResult.TRIGGERED, flag("speed", 'a', 12));
    }

    @Test
    void lagMidSequenceDiscards() {
        flag("speed", 'a', 12);
        flag("speed", 'a', 12);
        flag("killaura", 'a', 6);
        assertEquals(FlagResult.CONNECTION_DISCARD, esc.onFlag(s, UNSTABLE, "timer", 'a', 1, t += 1000));
        assertEquals(0, s.evidenceCount());
        flag("killaura", 'a', 6);
        assertEquals(FlagResult.EVIDENCE, flag("killaura", 'a', 6), "sequência recomeçou do zero");
        assertEquals(1, esc.connectionDiscards());
    }

    @Test
    void missingProviderDiscards() {
        flag("speed", 'a', 12);
        assertEquals(FlagResult.CONNECTION_DISCARD, esc.onFlag(s, NO_PROVIDER, "speed", 'a', 12, t += 1000));
        assertEquals(FlagResult.CONNECTION_DISCARD, esc.onFlag(s, null, "speed", 'a', 12, t += 1000));
        assertEquals(0, s.evidenceCount());
    }

    @Test
    void graceFlagsDoNotCount() {
        s.startGrace(t + 10_000, "teleporte");
        assertEquals(FlagResult.GRACE, flag("speed", 'a', 30));
        assertEquals(0, s.evidenceCount());
    }

    @Test
    void bedrockExcluded() {
        s = session(AccountKind.BEDROCK);
        for (int i = 0; i < 5; i++) {
            assertEquals(FlagResult.EXCLUDED, flag("speed", 'a', 30));
            assertEquals(FlagResult.EXCLUDED, flag("killaura", 'a', 30));
        }
        assertEquals(PlayerSession.Stage.IDLE, s.stage());
    }

    @Test
    void uncertainAccountStillCollects() {
        // Conta incerta junta evidências; a ação vira só registro para revisão (decidido no plugin).
        s = session(AccountKind.UNCERTAIN);
        assertEquals(FlagResult.TRIGGERED, fullSequence());
    }

    @Test
    void windowExpiresOldEvidence() {
        flag("speed", 'a', 12);
        flag("speed", 'a', 12);
        t += 61_000; // passou a janela
        flag("killaura", 'a', 6);
        flag("killaura", 'a', 6);
        assertEquals(FlagResult.EVIDENCE, flag("killaura", 'a', 6));
        assertEquals(PlayerSession.Stage.IDLE, s.stage());
    }

    @Test
    void onlyOneOccurrencePerSession() {
        assertEquals(FlagResult.TRIGGERED, fullSequence());
        assertEquals(FlagResult.SESSION_DONE, fullSequence());
        s.finish("kick");
        assertEquals(FlagResult.SESSION_DONE, fullSequence());
        assertEquals(1, esc.triggered());
    }

    @Test
    void resetAfterRevalidationStartsOver() {
        fullSequence();
        s.resetToIdle();
        assertEquals(PlayerSession.Stage.IDLE, s.stage());
        assertEquals(FlagResult.EVIDENCE, flag("speed", 'a', 12));
    }

    @Test
    void heavyFlaggingOfOneCheckNeverPushesOutTheOther() {
        // Um check que dispara centenas de vezes não pode apagar as flags do outro (memória fixa por check).
        flag("killaura", 'a', 6);
        flag("killaura", 'a', 6);
        for (int i = 0; i < 500; i++) {
            t += 10;
            esc.onFlag(s, STABLE, "speed", 'a', 30, t);
        }
        assertEquals(PlayerSession.Stage.IDLE, s.stage(), "killaura:a ainda só 2 de 3");
        assertTrue(s.evidenceCount() <= 2 + 2 + 3, "memória fixa: soma dos min-flags");
        assertEquals(FlagResult.TRIGGERED, flag("killaura", 'a', 6));
    }

    @Test
    void minFlagsMustFitInsideTheWindow() {
        flag("killaura", 'a', 6);
        t += 59_000;
        flag("killaura", 'a', 6);
        flag("speed", 'a', 12);
        flag("speed", 'a', 12);
        assertEquals(FlagResult.EVIDENCE, flag("killaura", 'a', 6), "a 1ª killaura saiu da janela");
        assertEquals(FlagResult.TRIGGERED, flag("killaura", 'a', 6));
    }

    @Test
    void reloadDiscardsOldEvidence() {
        flag("speed", 'a', 12);
        flag("speed", 'a', 12);
        flag("killaura", 'a', 6);
        flag("killaura", 'a', 6);
        esc.policy(EscalationPolicy.of(List.of(
                new EscalationPolicy.Spec("speed", "movimento", 10, 2),
                new EscalationPolicy.Spec("killaura:a", "combate", 5, 3)), 60_000, 2));
        assertEquals(FlagResult.EVIDENCE, flag("killaura", 'a', 6), "evidência antiga não vale com regras novas");
    }

    @Test
    void emptyPolicyNeverTriggers() {
        esc.policy(EscalationPolicy.empty());
        for (int i = 0; i < 20; i++) {
            assertEquals(FlagResult.NOT_APPROVED, flag("speed", 'a', 99));
            assertEquals(FlagResult.NOT_APPROVED, flag("killaura", 'a', 99));
        }
        assertFalse(EscalationPolicy.empty().canTrigger());
    }

    @Test
    void simultaneousFlagsTriggerExactlyOnce() throws Exception {
        EscalationPolicy p = EscalationPolicy.of(List.of(
                new EscalationPolicy.Spec("speed", "", 1, 2),
                new EscalationPolicy.Spec("reach", "", 1, 2)), 60_000, 2);
        esc.policy(p);
        for (int round = 0; round < 200; round++) {
            PlayerSession ses = new PlayerSession(UUID.randomUUID(), "x", round + 1, AccountKind.JAVA, "", 0L);
            AtomicInteger triggers = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String check = i % 2 == 0 ? "speed" : "reach";
                Thread th = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int k = 0; k < 20; k++) {
                        if (esc.onFlag(ses, STABLE, check, 'a', 5, 1_000L) == FlagResult.TRIGGERED) {
                            triggers.incrementAndGet();
                        }
                    }
                });
                threads.add(th);
                th.start();
            }
            start.countDown();
            for (Thread th : threads) {
                th.join();
            }
            assertEquals(1, triggers.get(), "rodada " + round);
        }
    }
}
