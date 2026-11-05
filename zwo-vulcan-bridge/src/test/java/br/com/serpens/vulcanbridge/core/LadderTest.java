package br.com.serpens.vulcanbridge.core;

import br.com.serpens.vulcanbridge.core.Ladder.Step;
import br.com.serpens.vulcanbridge.core.OffenderRecord.BanState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LadderTest {

    static final long DAY = 86_400_000L;
    static final long VALID = 7 * DAY;
    final UUID id = UUID.randomUUID();

    @Test
    void fullSequenceAcrossSessions() {
        long now = 10 * DAY;
        OffenderRecord r = null;
        assertEquals(Step.KICK_1, Ladder.next(r, now, VALID, 1));
        r = OffenderRecord.fresh(id, "x").withKick(1, now, "x");
        assertEquals(Step.KICK_2, Ladder.next(r, now + 1000, VALID, 2));
        r = r.withKick(2, now + 1000, "x");
        assertEquals(Step.TEMPBAN, Ladder.next(r, now + 2000, VALID, 3));
        r = r.withBan(BanState.PENDING, now + 2000, "req", "");
        assertEquals(Step.REVIEW_ONLY, Ladder.next(r, now + 3000, VALID, 4), "nunca outro ban automático");
    }

    @Test
    void neverTwoInTheSameSession() {
        OffenderRecord r = OffenderRecord.fresh(id, "x").withKick(5, 0, "x");
        assertEquals(Step.REVIEW_ONLY, Ladder.next(r, 1000, VALID, 5));
    }

    @Test
    void reviewAndAppliedBlockFurtherBans() {
        OffenderRecord r = OffenderRecord.fresh(id, "x").withKick(1, 0, "x").withKick(2, 1, "x");
        for (BanState st : List.of(BanState.PENDING, BanState.REVIEW, BanState.APPLIED)) {
            assertEquals(Step.REVIEW_ONLY, Ladder.next(r.withBan(st, 2, "q", ""), 3, VALID, 9), st.name());
        }
    }

    @Test
    void validityCountsFromFirstKick() {
        OffenderRecord r = OffenderRecord.fresh(id, "x").withKick(1, 0, "x").withKick(2, 6 * DAY, "x");
        assertEquals(0, r.firstKickAt());
        assertEquals(Step.TEMPBAN, Ladder.next(r, 7 * DAY - 1, VALID, 3));
        assertTrue(r.expired(7 * DAY, VALID));
        assertEquals(Step.KICK_1, Ladder.next(r, 7 * DAY, VALID, 3), "vencido: recomeça");
        OffenderRecord reviewed = r.withBan(BanState.REVIEW, 1, "q", "");
        assertEquals(Step.KICK_1, Ladder.next(reviewed, 8 * DAY, VALID, 3));
    }

    @Test
    void durationText() {
        assertEquals(DAY, DurationText.parse("24h"));
        assertEquals(VALID, DurationText.parse("7d"));
        assertEquals(DAY + 12 * 3_600_000L, DurationText.parse("1d12h"));
        assertEquals(90_000L, DurationText.parse("1m30s"));
        assertEquals(-1, DurationText.parse("24"));
        assertEquals(-1, DurationText.parse("h"));
        assertEquals(-1, DurationText.parse("0h"));
        assertEquals(-1, DurationText.parse("abc"));
        assertEquals("1d", DurationText.format(DAY));
        assertEquals("1d12h", DurationText.format(DAY + 12 * 3_600_000L));
    }

    @Test
    void policyParsingAndProblems() {
        EscalationPolicy p = EscalationPolicy.of(List.of(
                new EscalationPolicy.Spec("Speed", "", 10, 2),
                new EscalationPolicy.Spec("speed:b", "movimento", 12, 1),
                new EscalationPolicy.Spec("fly:xx", "", 5, 2),
                new EscalationPolicy.Spec("reach", "", 0, 2),
                new EscalationPolicy.Spec("speed", "", 3, 2)), 60_000, 1);
        assertEquals(2, p.rules().size());
        assertEquals(2, p.minFamilies(), "mínimo forçado em 2");
        assertEquals("speed:b", p.match("speed", 'B').key());
        assertEquals("speed", p.match("speed", 'a').key());
        assertEquals("speed", p.match("SPEED", 'c').key());
        assertEquals(null, p.match("fly", 'a'));
        assertTrue(p.canTrigger(), "speed e movimento são famílias diferentes");
        assertTrue(p.problems().size() >= 4, p.problems().toString());

        EscalationPolicy one = EscalationPolicy.of(List.of(new EscalationPolicy.Spec("speed", "", 10, 2)),
                60_000, 2);
        assertTrue(!one.canTrigger());
    }
}
