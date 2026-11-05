package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static br.com.serpens.vulcanbridge.core.FlagEngine.Outcome.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagEngineTest {

    private static final ConnectionStatus STABLE = ConnectionStatus.of(ConnectionState.STABLE, ConnectionReason.OK);
    private static final ConnectionStatus UNSTABLE = ConnectionStatus.of(ConnectionState.UNSTABLE, ConnectionReason.PING_SPIKES);
    private static final ConnectionStatus UNKNOWN = ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.STALE_DATA);
    private static final ConnectionStatus NO_PROVIDER =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.PROVIDER_UNAVAILABLE);

    // maxVl 20 * 0.6 = 12
    private static final FlagInfo HIGH = new FlagInfo("speed", 'a', "movement", 15, 20, true);
    private static final FlagInfo LOW = new FlagInfo("speed", 'a', "movement", 5, 20, true);

    private FlagEngine engine;
    private PlayerSession session;
    private long t;

    @BeforeEach
    void setUp() {
        // 5 flags altas (VL >= 60% do máximo, piso 8) em 30 s
        engine = new FlagEngine(new FlagPolicy(0.6, 8, 5, 30_000L, true, true, Set.of(), Set.of()));
        session = new PlayerSession(UUID.randomUUID(), "alvo", "s1", false, 0);
        t = 1_000_000;
    }

    private FlagEngine.Result flag(FlagInfo f, ConnectionStatus c) {
        t += 1000;
        return engine.onFlag(session, f, c, t);
    }

    @Test
    @DisplayName("STABLE: 5 flags altas em sequência confirmam uma ocorrência")
    void stableSequenceConfirms() {
        for (int i = 1; i <= 4; i++) {
            assertEquals(new FlagEngine.Result(COUNTED, i), flag(HIGH, STABLE));
        }
        assertEquals(OCCURRENCE, flag(HIGH, STABLE).outcome());
        assertEquals(IGNORED_SESSION_DONE, flag(HIGH, STABLE).outcome(), "no máximo uma ocorrência por sessão");
    }

    @Test
    @DisplayName("UNSTABLE descarta a sequência inteira")
    void unstableDiscards() {
        flag(HIGH, STABLE);
        flag(HIGH, STABLE);
        flag(HIGH, STABLE);
        assertEquals(DISCARDED_CONNECTION, flag(HIGH, UNSTABLE).outcome());
        assertEquals(0, session.view().sequence());
        assertEquals(1, flag(HIGH, STABLE).sequence(), "recomeça do zero");
    }

    @Test
    @DisplayName("UNKNOWN e provedor desligado também descartam")
    void unknownAndMissingProviderDiscard() {
        flag(HIGH, STABLE);
        assertEquals(DISCARDED_CONNECTION, flag(HIGH, UNKNOWN).outcome());
        flag(HIGH, STABLE);
        assertEquals(DISCARDED_CONNECTION, flag(HIGH, NO_PROVIDER).outcome());
        assertEquals(DISCARDED_CONNECTION, flag(HIGH, null).outcome());
        // nunca chega a ocorrência só com flags sem STABLE
        for (int i = 0; i < 50; i++) {
            assertEquals(DISCARDED_CONNECTION, flag(HIGH, NO_PROVIDER).outcome());
        }
        assertEquals(0, engine.occurrences());
    }

    @Test
    @DisplayName("flags baixas não contam, mas também não zeram")
    void lowFlags() {
        flag(HIGH, STABLE);
        assertEquals(LOW_VL, flag(LOW, STABLE).outcome());
        assertEquals(2, flag(HIGH, STABLE).sequence());
    }

    @Test
    @DisplayName("flags fora da janela de 30 s saem da sequência")
    void windowExpiry() {
        flag(HIGH, STABLE);
        flag(HIGH, STABLE);
        t += 40_000;
        assertEquals(1, flag(HIGH, STABLE).sequence());
    }

    @Test
    @DisplayName("proteção de teleporte/respawn descarta a sequência")
    void graceDiscards() {
        flag(HIGH, STABLE);
        flag(HIGH, STABLE);
        session.startGrace(t + 3000, "teleporte");
        assertEquals(DISCARDED_GRACE, flag(HIGH, STABLE).outcome());
        t += 5000;
        assertEquals(1, flag(HIGH, STABLE).sequence());
    }

    @Test
    @DisplayName("Bedrock nunca entra na escada")
    void bedrockIgnored() {
        PlayerSession bedrock = new PlayerSession(new UUID(0, 1), ".bedrock", "s", true, 0);
        for (int i = 0; i < 20; i++) {
            assertEquals(IGNORED_BEDROCK, engine.onFlag(bedrock, HIGH, STABLE, t + i * 100L).outcome());
        }
    }

    @Test
    @DisplayName("checks não puníveis e ignorados no config não contam")
    void ignoredChecks() {
        assertEquals(IGNORED_CHECK, flag(new FlagInfo("speed", 'a', "movement", 15, 20, false), STABLE).outcome());
        engine.policy(new FlagPolicy(0.6, 8, 5, 30_000, true, true, Set.of("timer", "speed:b"), Set.of("player")));
        assertEquals(IGNORED_CHECK, flag(new FlagInfo("Timer", 'a', "movement", 15, 20, true), STABLE).outcome());
        assertEquals(IGNORED_CHECK, flag(new FlagInfo("speed", 'B', "movement", 15, 20, true), STABLE).outcome());
        assertEquals(IGNORED_CHECK, flag(new FlagInfo("inventory", 'a', "Player", 15, 20, true), STABLE).outcome());
        assertEquals(COUNTED, flag(HIGH, STABLE).outcome());
    }
}
