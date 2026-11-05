package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateTest {

    private static final long NOW = 1_000_000L;
    private static final ConnectionStatus STABLE = ConnectionStatus.of(ConnectionState.STABLE, ConnectionReason.OK);
    private static final ConnectionStatus HIGH = ConnectionStatus.of(ConnectionState.UNSTABLE, ConnectionReason.HIGH_PING);
    private static final ConnectionStatus NEW = ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.INSUFFICIENT_DATA);
    private static final ConnectionStatus NO_PROVIDER =
            ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.PROVIDER_UNAVAILABLE);

    /** ENFORCE com o padrão do config: flags mantidas, lag recente de 120 s. */
    private static Gate enforce() {
        return new Gate(new Gate.Policy(BridgeMode.ENFORCE, false, true, 120_000L));
    }

    private static Gate enforceCancellingFlags() {
        return new Gate(new Gate.Policy(BridgeMode.ENFORCE, true, true, 120_000L));
    }

    private static PlayerSession java() {
        return new PlayerSession(UUID.randomUUID(), "java", false, 0L);
    }

    private static PlayerSession bedrock() {
        return new PlayerSession(UUID.randomUUID(), ".bedrock", true, 0L);
    }

    @Test
    @DisplayName("ENFORCE + STABLE: a punição do Vulcan passa")
    void stablePunishmentPasses() {
        Gate g = enforce();
        Gate.Decision d = g.onPunish(java(), STABLE, NOW);
        assertFalse(d.cancel());
        assertEquals(GateReason.ALLOWED, d.reason());
        assertEquals(1, g.punishAllowed());
    }

    @Test
    @DisplayName("lag (UNSTABLE), UNKNOWN e provedor ausente cancelam a punição")
    void badConnectionBlocksPunishment() {
        Gate g = enforce();
        assertEquals(new Gate.Decision(true, GateReason.CONNECTION_UNSTABLE), g.onPunish(java(), HIGH, NOW));
        assertEquals(new Gate.Decision(true, GateReason.CONNECTION_UNKNOWN), g.onPunish(java(), NEW, NOW));
        assertEquals(new Gate.Decision(true, GateReason.PROVIDER_UNAVAILABLE), g.onPunish(java(), NO_PROVIDER, NOW));
        assertEquals(new Gate.Decision(true, GateReason.PROVIDER_UNAVAILABLE), g.onPunish(java(), null, NOW));
        assertEquals(0, g.punishAllowed());
        assertEquals(4, g.punishBlockedTotal());
    }

    @Test
    @DisplayName("padrão: a flag com lag segue (setback e alerta do Vulcan continuam), a punição é barrada")
    void flagsKeptByDefault() {
        Gate g = enforce();
        assertFalse(g.onFlag(java(), HIGH, NOW).cancel());
        assertTrue(g.onPunish(java(), HIGH, NOW).cancel());
        assertEquals(0, g.flagsCancelled());
    }

    @Test
    @DisplayName("cancel-flags ligado: a flag com lag é cancelada")
    void flagCancellingOptional() {
        Gate g = enforceCancellingFlags();
        assertTrue(g.onFlag(java(), HIGH, NOW).cancel());
        assertFalse(g.onFlag(java(), STABLE, NOW).cancel());
        assertEquals(1, g.flagsCancelled());
    }

    @Test
    @DisplayName("lag recente: flag com lag há menos de 120 s bloqueia a punição mesmo já STABLE")
    void recentLagBlocks() {
        Gate g = enforce();
        PlayerSession s = java();
        g.onFlag(s, HIGH, NOW);
        assertEquals(new Gate.Decision(true, GateReason.RECENT_LAG), g.onPunish(s, STABLE, NOW + 60_000));
        assertFalse(g.onPunish(s, STABLE, NOW + 120_000).cancel(), "depois da janela a punição passa");
    }

    @Test
    @DisplayName("UNKNOWN logo após entrar bloqueia na hora, mas não conta como lag recente")
    void joinUnknownIsNotLag() {
        Gate g = enforce();
        PlayerSession s = java();
        g.onFlag(s, NEW, NOW);
        assertEquals(new Gate.Decision(true, GateReason.CONNECTION_UNKNOWN), g.onPunish(s, NEW, NOW));
        assertFalse(g.onPunish(s, STABLE, NOW + 10_000).cancel(), "já STABLE: a punição passa");
    }

    @Test
    @DisplayName("lag recente não se aplica quando as flags com lag já são canceladas")
    void recentLagIgnoredWhenFlagsCancelled() {
        Gate g = enforceCancellingFlags();
        PlayerSession s = java();
        g.onFlag(s, HIGH, NOW);
        assertFalse(g.onPunish(s, STABLE, NOW + 1000).cancel());
    }

    @Test
    @DisplayName("zerar VL: só em ENFORCE e só para bloqueios de lag/proteção/Bedrock")
    void resetVlRules() {
        Gate e = enforce();
        assertTrue(e.shouldResetVl(e.onPunish(java(), HIGH, NOW)));
        assertTrue(e.shouldResetVl(e.onPunish(bedrock(), STABLE, NOW)));
        assertFalse(e.shouldResetVl(e.onPunish(java(), STABLE, NOW)), "punição liberada");
        assertFalse(e.shouldResetVl(e.onPunish(null, STABLE, NOW)), "sem sessão");
        Gate o = new Gate(Gate.Policy.defaults());
        assertFalse(o.shouldResetVl(o.onPunish(java(), HIGH, NOW)), "OBSERVE não mexe no Vulcan");
    }

    @Test
    @DisplayName("OBSERVE: toda punição é cancelada; com conexão estável fica como 'teria passado'")
    void observeBlocksEverything() {
        Gate g = new Gate(Gate.Policy.defaults());
        assertEquals(new Gate.Decision(true, GateReason.OBSERVE_MODE), g.onPunish(java(), STABLE, NOW));
        assertEquals(new Gate.Decision(true, GateReason.CONNECTION_UNSTABLE), g.onPunish(java(), HIGH, NOW));
        assertEquals(0, g.punishAllowed());
    }

    @Test
    @DisplayName("OBSERVE nunca cancela flags (alertas do Vulcan seguem normais)")
    void observeKeepsFlags() {
        Gate g = new Gate(Gate.Policy.defaults());
        Gate.Decision d = g.onFlag(java(), HIGH, NOW);
        assertFalse(d.cancel());
        assertEquals(GateReason.CONNECTION_UNSTABLE, d.reason());
        assertEquals(1, g.flagsWouldCancel());
    }

    @Test
    @DisplayName("Bedrock nunca é punido automaticamente, mesmo com conexão estável")
    void bedrockNeverPunished() {
        Gate g = enforce();
        assertEquals(new Gate.Decision(true, GateReason.BEDROCK), g.onPunish(bedrock(), STABLE, NOW));
        assertFalse(g.onFlag(bedrock(), STABLE, NOW).cancel(), "a flag segue para alertar a staff");
    }

    @Test
    @DisplayName("proteção de teleporte/respawn cancela flag e punição")
    void protectionBlocks() {
        Gate g = enforceCancellingFlags();
        PlayerSession s = java();
        s.startGrace(NOW + 3000, "teleporte");
        assertEquals(new Gate.Decision(true, GateReason.PROTECTION), g.onFlag(s, STABLE, NOW));
        assertEquals(new Gate.Decision(true, GateReason.PROTECTION), g.onPunish(s, STABLE, NOW));
        assertFalse(g.onPunish(s, STABLE, NOW + 3000).cancel(), "a proteção acaba no prazo");
    }

    @Test
    @DisplayName("log/aviso limitado a 1 por check a cada 30 s")
    void reportRateLimit() {
        PlayerSession s = java();
        assertTrue(s.shouldReport("speed:e|ALLOWED", NOW, 30_000));
        assertFalse(s.shouldReport("speed:e|ALLOWED", NOW + 1000, 30_000));
        assertTrue(s.shouldReport("fly:a|ALLOWED", NOW + 1000, 30_000));
        assertTrue(s.shouldReport("fly:a|ALLOWED", NOW + 31_000, 30_000));
    }

    @Test
    @DisplayName("jogador sem sessão: punição cancelada")
    void noSession() {
        assertEquals(new Gate.Decision(true, GateReason.NO_SESSION), enforce().onPunish(null, STABLE, NOW));
    }

    @Test
    @DisplayName("diagnóstico por jogador registra o último motivo")
    void sessionDiagnostics() {
        Gate g = enforce();
        PlayerSession s = java();
        g.onFlag(s, HIGH, NOW);
        g.onPunish(s, HIGH, NOW + 1);
        g.onPunish(s, STABLE, NOW + 200_000);
        assertEquals(1, s.flagsFiltered());
        assertEquals(1, s.punishmentsBlocked());
        assertEquals(1, s.punishmentsAllowed());
        assertEquals(GateReason.ALLOWED, s.lastPunishReason());
    }

    @Test
    @DisplayName("modo: valores aceitos e padrão seguro")
    void modeParsing() {
        assertEquals(BridgeMode.OBSERVE, BridgeMode.parse(null));
        assertEquals(BridgeMode.OBSERVE, BridgeMode.parse("qualquer"));
        assertEquals(BridgeMode.ENFORCE, BridgeMode.parse("enforce"));
        assertEquals(BridgeMode.ENFORCE, BridgeMode.parse("FILTRAR"));
    }
}
