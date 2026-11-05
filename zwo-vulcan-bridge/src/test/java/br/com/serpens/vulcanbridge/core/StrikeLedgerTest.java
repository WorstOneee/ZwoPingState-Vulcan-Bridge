package br.com.serpens.vulcanbridge.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static br.com.serpens.vulcanbridge.core.BridgeMode.ENFORCE;
import static br.com.serpens.vulcanbridge.core.BridgeMode.OBSERVE;
import static br.com.serpens.vulcanbridge.core.StrikeLedger.Step.BAN;
import static br.com.serpens.vulcanbridge.core.StrikeLedger.Step.KICK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrikeLedgerTest {

    private static final long DAY = 86_400_000L;
    private final UUID id = UUID.randomUUID();

    private static Strike kick(long at, String session, BridgeMode mode) {
        return new Strike(at, session, Strike.Action.KICK, mode, "speed A");
    }

    @Test
    @DisplayName("kick, kick (sessões distintas) e só então ban por perfil")
    void ladder() {
        StrikeLedger l = new StrikeLedger(2, 30 * DAY);
        assertEquals(new StrikeLedger.Decision(KICK, 0), l.decide(id, ENFORCE, "s1", 1000));
        l.record(id, kick(1000, "s1", ENFORCE));
        assertEquals(new StrikeLedger.Decision(KICK, 1), l.decide(id, ENFORCE, "s2", 2000));
        l.record(id, kick(2000, "s2", ENFORCE));
        assertEquals(new StrikeLedger.Decision(BAN, 2), l.decide(id, ENFORCE, "s3", 3000));
    }

    @Test
    @DisplayName("dois kicks na mesma sessão contam como um só")
    void sameSessionCountsOnce() {
        StrikeLedger l = new StrikeLedger(2, 30 * DAY);
        l.record(id, kick(1000, "s1", ENFORCE));
        l.record(id, kick(1100, "s1", ENFORCE));
        assertEquals(1, l.validKicks(id, ENFORCE, 2000));
        assertEquals(KICK, l.decide(id, ENFORCE, "s2", 2000).step());
    }

    @Test
    @DisplayName("a 3ª ocorrência na mesma sessão de um kick não vira ban")
    void banNeedsDistinctSession() {
        StrikeLedger l = new StrikeLedger(2, 30 * DAY);
        l.record(id, kick(1000, "s1", ENFORCE));
        l.record(id, kick(2000, "s2", ENFORCE));
        assertEquals(KICK, l.decide(id, ENFORCE, "s2", 3000).step());
    }

    @Test
    @DisplayName("strikes do modo observação nunca contam no modo de punição")
    void observeDoesNotLeakIntoEnforce() {
        StrikeLedger l = new StrikeLedger(2, 30 * DAY);
        l.record(id, kick(1000, "s1", OBSERVE));
        l.record(id, kick(2000, "s2", OBSERVE));
        assertEquals(BAN, l.decide(id, OBSERVE, "s3", 3000).step());
        assertEquals(new StrikeLedger.Decision(KICK, 0), l.decide(id, ENFORCE, "s3", 3000));
    }

    @Test
    @DisplayName("strikes vencidos deixam de contar")
    void expiry() {
        StrikeLedger l = new StrikeLedger(2, 30 * DAY);
        l.record(id, kick(0, "s1", ENFORCE));
        l.record(id, kick(DAY, "s2", ENFORCE));
        // 30,5 dias depois: o primeiro (dia 0) venceu, o segundo (dia 1) ainda vale
        assertEquals(1, l.validKicks(id, ENFORCE, 30 * DAY + DAY / 2));
        assertEquals(1, l.purgeExpired(31 * DAY));
        assertEquals(KICK, l.decide(id, ENFORCE, "s3", 31 * DAY).step());
    }

    @Test
    void clear() {
        StrikeLedger l = new StrikeLedger(2, 0);
        l.record(id, kick(0, "s1", ENFORCE));
        assertTrue(l.clear(id));
        assertFalse(l.clear(id));
        assertEquals(0, l.validKicks(id, ENFORCE, 0));
    }

    @Test
    void serializeRoundTrip() {
        Strike s = new Strike(123L, "abc", Strike.Action.BAN, ENFORCE, "killaura A|x");
        Strike back = Strike.parse(s.serialize());
        assertEquals(123L, back.atMillis());
        assertEquals(Strike.Action.BAN, back.action());
        assertEquals("killaura A/x", back.check());
        assertThrows(IllegalArgumentException.class, () -> Strike.parse("lixo"));
    }
}
