package dev.zwo.pingstate.bridge;

import br.com.serpens.vulcanbridge.api.ConnectionSnapshot;
import br.com.serpens.vulcanbridge.api.PingStateProvider;
import br.com.serpens.vulcanbridge.core.AccountKind;
import br.com.serpens.vulcanbridge.core.Escalation;
import br.com.serpens.vulcanbridge.core.Escalation.FlagResult;
import br.com.serpens.vulcanbridge.core.EscalationPolicy;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import dev.zwo.pingstate.core.PingSettings;
import dev.zwo.pingstate.core.PingTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ponta a ponta: coleta real do ZwoPingState + provedor real + escalada real da ponte, com relógio
 * simulado. O caso central: flag estável → lag SEM nenhuma flag → recuperação → nova flag. A
 * evidência anterior não pode completar a ocorrência.
 */
class LagBetweenFlagsTest {

    private static final long TICK = 50_000_000L;
    private static final long GEN = 1L;

    private long now;
    private PingTracker tracker;
    private PingStateProvider provider;
    private Escalation esc;
    private PlayerSession session;
    private final UUID id = UUID.randomUUID();
    private final AtomicInteger ping = new AtomicInteger(40);
    private final AtomicBoolean failRead = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        now = 1_000_000_000_000L;
        tracker = new PingTracker(PingSettings.defaults(), () -> now);
        provider = new BridgeProvider(tracker, () -> now);
        esc = new Escalation(EscalationPolicy.of(List.of(
                new EscalationPolicy.Spec("speed", "movimento", 1, 2),
                new EscalationPolicy.Spec("killaura", "combate", 1, 2)), 120_000, 2));
        join();
    }

    private void join() {
        tracker.register(id, "x", false, () -> {
            if (failRead.get()) {
                throw new IllegalStateException("falha simulada");
            }
            return ping.get();
        }, now);
        session = new PlayerSession(id, "x", now, AccountKind.JAVA, "", 0L);
        seconds(12);
        assertTrue(provider.snapshot(id).usable(), "começa STABLE");
    }

    private void seconds(double s) {
        int n = (int) Math.round(s * 20);
        for (int i = 0; i < n; i++) {
            now += TICK;
            tracker.tick(now);
            tracker.tickEnd(now, 20.0);
        }
    }

    private FlagResult flag(String check) {
        now += 1_000_000L; // sem avançar ticks: não pode parecer queda de TPS
        return esc.onFlag(session, provider.snapshot(id), GEN, check, 'a', 5, now / 1_000_000L);
    }

    /** Três das quatro evidências de uma ocorrência: falta só uma killaura. */
    private void almostComplete() {
        assertEquals(FlagResult.EVIDENCE, flag("speed"));
        assertEquals(FlagResult.EVIDENCE, flag("speed"));
        assertEquals(FlagResult.EVIDENCE, flag("killaura"));
    }

    private void lagWithoutFlagsThenRecover() {
        ping.set(450);
        seconds(4);
        ping.set(40);
        seconds(35);
        assertTrue(provider.snapshot(id).usable(), "recuperou: STABLE de novo");
    }

    @Test
    @DisplayName("flag estável → lag sem flag → recuperação → nova flag: evidência anterior descartada")
    void lagBetweenFlags() {
        almostComplete();
        lagWithoutFlagsThenRecover();
        assertEquals(FlagResult.RESTARTED, flag("killaura"), "a recuperação não completa a sequência antiga");
        assertEquals(PlayerSession.Stage.IDLE, session.stage());
        assertEquals(1, session.evidenceCount(), "só a flag atual, como sequência NOVA");
        assertEquals(1, esc.revisionDiscards());
        // A nova sequência, inteira depois do lag, vale normalmente.
        assertEquals(FlagResult.EVIDENCE, flag("killaura"));
        assertEquals(FlagResult.EVIDENCE, flag("speed"));
        assertEquals(FlagResult.TRIGGERED, flag("speed"));
    }

    @Test
    @DisplayName("mesmo cenário com travada global do servidor")
    void globalStallBetweenFlags() {
        almostComplete();
        now += 700_000_000L; // um tick de 700 ms
        tracker.tick(now);
        seconds(15);
        assertTrue(provider.snapshot(id).usable());
        assertEquals(FlagResult.RESTARTED, flag("killaura"));
        assertEquals(PlayerSession.Stage.IDLE, session.stage());
    }

    @Test
    @DisplayName("UNKNOWN entre as flags (falha de coleta) e retorno a STABLE")
    void unknownBetweenFlags() {
        almostComplete();
        failRead.set(true);
        seconds(1.5);
        assertFalse(provider.snapshot(id).usable());
        failRead.set(false);
        seconds(25);
        assertTrue(provider.snapshot(id).usable());
        assertEquals(FlagResult.RESTARTED, flag("killaura"));
    }

    @Test
    @DisplayName("amostras vencidas e retomada da coleta")
    void staleThenResume() {
        almostComplete();
        now += 6_000_000_000L; // coleta parada por 6 s, nenhuma flag
        ConnectionSnapshot stale = provider.snapshot(id);
        assertFalse(stale.fresh());
        assertFalse(stale.usable());
        seconds(25); // coleta retomada; volta a STABLE
        assertTrue(provider.snapshot(id).usable());
        assertEquals(FlagResult.RESTARTED, flag("killaura"));
    }

    @Test
    @DisplayName("flag durante a falta de dados descarta na hora")
    void flagWhileStaleDiscards() {
        almostComplete();
        now += 6_000_000_000L;
        assertEquals(FlagResult.CONNECTION_DISCARD, flag("killaura"));
        assertEquals(0, session.evidenceCount());
    }

    @Test
    @DisplayName("provedor reiniciado/substituído: evidências antigas deixam de valer")
    void providerRestarted() {
        almostComplete();
        // ZwoPingState recarregado: coletor e provedor novos (outro epoch), mesmo jogador.
        tracker = new PingTracker(PingSettings.defaults(), () -> now);
        provider = new BridgeProvider(tracker, () -> now);
        tracker.register(id, "x", false, ping::get, now);
        seconds(12);
        assertTrue(provider.snapshot(id).usable());
        assertEquals(FlagResult.RESTARTED, flag("killaura"));
    }

    @Test
    @DisplayName("provedor trocado na ponte (outra geração), mesmo snapshot: descarta")
    void providerGenerationChanged() {
        almostComplete();
        now += TICK;
        assertEquals(FlagResult.RESTARTED,
                esc.onFlag(session, provider.snapshot(id), GEN + 1, "killaura", 'a', 5, now / 1_000_000L));
    }

    @Test
    @DisplayName("provedor removido: sem dados utilizáveis, a flag descarta")
    void providerRemoved() {
        almostComplete();
        tracker.clear(); // como no onDisable do ZwoPingState
        assertEquals(FlagResult.CONNECTION_DISCARD, flag("killaura"));
        assertEquals(0, session.evidenceCount());
    }

    @Test
    @DisplayName("provedor antigo (sem revisões): nada conta e a revalidação recusa")
    void legacyProviderIncompatible() {
        PingStateProvider legacy = new LegacyBridgeProvider(tracker);
        assertEquals(0, legacy.snapshotVersion());
        ConnectionSnapshot s = legacy.snapshot(id);
        assertFalse(s.revisionsSupported(), "ausência de revisão não é 'sem lag'");
        for (int i = 0; i < 6; i++) {
            assertEquals(FlagResult.PROVIDER_INCOMPATIBLE,
                    esc.onFlag(session, legacy.snapshot(id), GEN, i % 2 == 0 ? "speed" : "killaura", 'a', 5, i));
        }
        assertEquals(0, session.evidenceCount());
        assertNotNull(Escalation.revalidate(session, s, GEN, 0L));
    }

    @Test
    @DisplayName("jogador desconecta e reconecta: nova sessão de coleta, sequência nova")
    void reconnect() {
        almostComplete();
        long oldSession = provider.snapshot(id).sessionId();
        tracker.unregister(id);
        assertEquals(FlagResult.CONNECTION_DISCARD, flag("killaura"), "fora da coleta: descarta");
        PlayerSession oldBridgeSession = session;
        join(); // nova sessão na coleta e na ponte
        assertTrue(provider.snapshot(id).sessionId() != oldSession);
        assertEquals(0, session.evidenceCount());
        assertEquals(PlayerSession.Stage.IDLE, oldBridgeSession.stage());
        // E mesmo que a sessão antiga da ponte recebesse uma flag agora, a âncora não bate.
        assertEquals(FlagResult.EVIDENCE, flag("killaura"));
    }

    @Test
    @DisplayName("lag detectado depois da última flag, antes da ação: não pode punir")
    void lagAfterTriggerBeforeAction() {
        almostComplete();
        assertEquals(FlagResult.TRIGGERED, flag("killaura"));
        assertNull(Escalation.revalidate(session, provider.snapshot(id), GEN, now / 1_000_000L),
                "sem lag: a ação pode seguir");

        ping.set(450);
        seconds(3); // lag detectado antes da tarefa da ação rodar
        assertNotNull(Escalation.revalidate(session, provider.snapshot(id), GEN, now / 1_000_000L));
        ping.set(40);
        seconds(35);
        assertTrue(provider.snapshot(id).usable());
        String why = Escalation.revalidate(session, provider.snapshot(id), GEN, now / 1_000_000L);
        assertNotNull(why, "mesmo recuperado, a ocorrência antiga não pode agir");
        assertTrue(why.contains("instabilidade"), why);
    }

    @Test
    @DisplayName("travada global depois do disparo, antes da ação: não pode punir")
    void stallAfterTrigger() {
        almostComplete();
        assertEquals(FlagResult.TRIGGERED, flag("killaura"));
        now += 700_000_000L;
        tracker.tick(now);
        seconds(15);
        assertNotNull(Escalation.revalidate(session, provider.snapshot(id), GEN, now / 1_000_000L));
    }

    @Test
    @DisplayName("sequência estável legítima continua formando ocorrência")
    void stableSequenceStillTriggers() {
        almostComplete();
        seconds(5); // tempo passando, tudo estável
        assertEquals(FlagResult.TRIGGERED, flag("killaura"));
        assertNull(Escalation.revalidate(session, provider.snapshot(id), GEN, now / 1_000_000L));
        assertEquals(0, esc.revisionDiscards());
    }

    @Test
    @DisplayName("lag de outro jogador não afeta a sequência deste")
    void otherPlayerLagIsolated() {
        UUID other = UUID.randomUUID();
        AtomicInteger otherPing = new AtomicInteger(40);
        tracker.register(other, "y", false, otherPing::get, now);
        seconds(12);
        almostComplete();
        otherPing.set(600);
        seconds(5);
        assertEquals(FlagResult.TRIGGERED, flag("killaura"));
    }
}
