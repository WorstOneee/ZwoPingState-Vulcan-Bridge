package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionSnapshot;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import br.com.serpens.vulcanbridge.api.PingStateProvider;
import br.com.serpens.vulcanbridge.core.Escalation.FlagResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Âncora da sequência: sessão de coleta, revisões, epoch e geração do provedor. */
class SnapshotAnchorTest {

    static final ConnectionStatus OK = ConnectionStatus.of(ConnectionState.STABLE, ConnectionReason.OK);

    long epoch = 10, collect = 3, playerRev = 1, serverRev = 0, gen = 1;
    boolean fresh = true;
    Escalation esc;
    PlayerSession s;
    long t = 1_000_000L;

    @BeforeEach
    void setUp() {
        esc = new Escalation(EscalationPolicy.of(List.of(
                new EscalationPolicy.Spec("speed", "movimento", 1, 2),
                new EscalationPolicy.Spec("reach", "combate", 1, 2)), 120_000, 2));
        s = new PlayerSession(UUID.randomUUID(), "x", 1L, AccountKind.JAVA, "", 0L);
    }

    ConnectionSnapshot snap() {
        return new ConnectionSnapshot(OK, epoch, collect, playerRev, serverRev, 100, fresh, true);
    }

    FlagResult flag(String c) {
        return esc.onFlag(s, snap(), gen, c, 'a', 5, t += 1000);
    }

    void three() {
        assertEquals(FlagResult.EVIDENCE, flag("speed"));
        assertEquals(FlagResult.EVIDENCE, flag("speed"));
        assertEquals(FlagResult.EVIDENCE, flag("reach"));
    }

    @Test
    void sameAnchorTriggers() {
        three();
        assertEquals(FlagResult.TRIGGERED, flag("reach"));
        assertNull(Escalation.revalidate(s, snap(), gen, t));
    }

    @Test
    void playerRevisionChangeRestarts() {
        three();
        playerRev++;
        assertEquals(FlagResult.RESTARTED, flag("reach"));
        assertEquals(1, s.evidenceCount());
        assertEquals(FlagResult.EVIDENCE, flag("reach"));
        assertEquals(FlagResult.EVIDENCE, flag("speed"));
        assertEquals(FlagResult.TRIGGERED, flag("speed"), "sequência nova, inteira depois do lag");
    }

    @Test
    void serverRevisionChangeRestarts() {
        three();
        serverRev++;
        assertEquals(FlagResult.RESTARTED, flag("reach"));
    }

    @Test
    void epochSessionAndGenerationChangesRestart() {
        three();
        epoch = 11;
        assertEquals(FlagResult.RESTARTED, flag("reach"));
        flag("speed");
        flag("speed");
        collect = 4;
        assertEquals(FlagResult.RESTARTED, flag("reach"));
        flag("speed");
        flag("speed");
        gen = 2;
        assertEquals(FlagResult.RESTARTED, flag("reach"));
        assertEquals(3, esc.revisionDiscards());
    }

    @Test
    void staleSnapshotDiscards() {
        three();
        fresh = false;
        assertEquals(FlagResult.CONNECTION_DISCARD, flag("reach"));
        assertEquals(0, s.evidenceCount());
    }

    @Test
    void unsupportedSnapshotNeverCounts() {
        ConnectionSnapshot legacy = ConnectionSnapshot.unsupported(OK);
        assertFalse(legacy.usable(), "STABLE sem revisões não vale");
        for (int i = 0; i < 10; i++) {
            assertEquals(FlagResult.PROVIDER_INCOMPATIBLE,
                    esc.onFlag(s, legacy, gen, i % 2 == 0 ? "speed" : "reach", 'a', 5, t += 1000));
        }
        assertEquals(0, s.evidenceCount());
        assertEquals(10, esc.incompatibleFlags());
    }

    @Test
    void defaultProviderMethodsAreUnsupported() {
        PingStateProvider old = id -> ConnectionState.STABLE; // provedor da API antiga
        assertEquals(0, old.snapshotVersion());
        assertFalse(old.snapshot(UUID.randomUUID()).revisionsSupported());
        assertFalse(old.snapshot(UUID.randomUUID()).usable());
    }

    @Test
    void unavailableIsDiscardNotIncompatible() {
        three();
        assertEquals(FlagResult.CONNECTION_DISCARD, esc.onFlag(s,
                ConnectionSnapshot.unavailable(ConnectionReason.PROVIDER_UNAVAILABLE), gen, "reach", 'a', 5, t));
        assertEquals(0, s.evidenceCount());
    }

    @Test
    void revalidationAfterTrigger() {
        three();
        assertEquals(FlagResult.TRIGGERED, flag("reach"));
        playerRev++;
        String why = Escalation.revalidate(s, snap(), gen, t);
        assertNotNull(why);
        assertTrue(why.contains("instabilidade da conexão"), why);
        playerRev--; // (não acontece na prática; só confere que compara a âncora exata)
        serverRev++;
        assertTrue(Escalation.revalidate(s, snap(), gen, t).contains("servidor"));
        serverRev--;
        fresh = false;
        assertTrue(Escalation.revalidate(s, snap(), gen, t).contains("vencida"));
        fresh = true;
        assertNull(Escalation.revalidate(s, snap(), gen, t));
        s.startGrace(t + 5000, "teleporte");
        assertTrue(Escalation.revalidate(s, snap(), gen, t).contains("teleporte"));
    }

    @Test
    void resetClearsAnchor() {
        three();
        assertEquals(FlagResult.TRIGGERED, flag("reach"));
        s.resetToIdle();
        playerRev += 5;
        assertEquals(FlagResult.EVIDENCE, flag("speed"), "depois do reset, nova âncora (não é RESTARTED)");
    }
}
