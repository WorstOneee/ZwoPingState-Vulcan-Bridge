package dev.zwo.pingstate.bridge;

import br.com.serpens.vulcanbridge.api.ConnectionSnapshot;
import br.com.serpens.vulcanbridge.core.AccountKind;
import br.com.serpens.vulcanbridge.core.Escalation;
import br.com.serpens.vulcanbridge.core.EscalationPolicy;
import br.com.serpens.vulcanbridge.core.PlayerSession;
import dev.zwo.pingstate.core.PingSettings;
import dev.zwo.pingstate.core.PingTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordem de grandeza do caminho da flag na ponte 3.1: um snapshot do provedor + a escalada.
 * Fora do servidor (sem Vulcan, sem Bukkit). Números no stdout do relatório.
 */
class SnapshotCostTest {

    @Test
    @DisplayName("custo do snapshot e de uma flag (snapshot + escalada)")
    void measure() {
        long[] clock = {1_000_000_000_000L};
        PingTracker t = new PingTracker(PingSettings.defaults(), () -> clock[0]);
        BridgeProvider p = new BridgeProvider(t, () -> clock[0]);
        int players = 100;
        UUID[] ids = new UUID[players];
        PlayerSession[] ses = new PlayerSession[players];
        for (int i = 0; i < players; i++) {
            ids[i] = UUID.randomUUID();
            t.register(ids[i], "p" + i, false, () -> 40, clock[0]);
            ses[i] = new PlayerSession(ids[i], "p" + i, i + 1, AccountKind.JAVA, "", 0L);
        }
        for (int i = 0; i < 400; i++) {
            clock[0] += 50_000_000L;
            t.tick(clock[0]);
            t.tickEnd(clock[0], 20.0);
        }
        // min-flags alto: as flags viram evidência sem disparar (o caminho mais caro).
        Escalation esc = new Escalation(EscalationPolicy.of(List.of(
                new EscalationPolicy.Spec("speed", "a", 1, 32),
                new EscalationPolicy.Spec("reach", "b", 1, 32)), 120_000, 2));

        long sink = 0;
        for (int i = 0; i < 2_000_000; i++) { // aquecimento
            sink += p.snapshot(ids[i % players]).playerRevision();
        }
        int n = 4_000_000;
        long s0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ConnectionSnapshot s = p.snapshot(ids[i % players]);
            sink += s.serverRevision();
        }
        double snapNs = (double) (System.nanoTime() - s0) / n;

        for (int i = 0; i < 1_000_000; i++) {
            int k = i % players;
            esc.onFlag(ses[k], p.snapshot(ids[k]), 1L, (i & 1) == 0 ? "speed" : "timer", 'a', 5, i);
        }
        long f0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int k = i % players;
            // metade aprovada (vira evidência), metade não aprovada
            esc.onFlag(ses[k], p.snapshot(ids[k]), 1L, (i & 1) == 0 ? "speed" : "timer", 'a', 5, 2_000_000L + i);
        }
        double flagNs = (double) (System.nanoTime() - f0) / n;
        System.out.printf("[custo-flag] snapshot %.1f ns | flag (snapshot + escalada) %.1f ns | sink %d%n",
                snapNs, flagNs, sink % 2);
        assertTrue(flagNs < 50_000, "caminho da flag deveria ficar em ns/poucos µs");
    }
}
