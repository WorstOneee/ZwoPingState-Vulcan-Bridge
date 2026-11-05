package dev.zwo.pingstate.bridge;

import br.com.serpens.vulcanbridge.api.ConnectionReason;
import br.com.serpens.vulcanbridge.api.ConnectionState;
import br.com.serpens.vulcanbridge.api.ConnectionStatus;
import dev.zwo.pingstate.core.PingReason;
import dev.zwo.pingstate.core.PingSettings;
import dev.zwo.pingstate.core.PingTracker;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgeProviderTest {

    @Test
    void everyReasonHasSameNamedBridgeReason() {
        for (PingReason r : PingReason.values()) {
            assertDoesNotThrow(() -> ConnectionReason.valueOf(r.name()), "falta ConnectionReason." + r.name());
        }
    }

    @Test
    void untrackedAndNullAreUnknown() {
        BridgeProvider p = new BridgeProvider(new PingTracker(PingSettings.defaults(), System::nanoTime));
        assertEquals(ConnectionState.UNKNOWN, p.connection(UUID.randomUUID()));
        assertEquals(ConnectionStatus.of(ConnectionState.UNKNOWN, ConnectionReason.NOT_TRACKED), p.status(UUID.randomUUID()));
        assertEquals(ConnectionState.UNKNOWN, p.status(null).state());
    }

    @Test
    void stableIsMapped() {
        PingTracker t = new PingTracker(PingSettings.defaults(), System::nanoTime);
        UUID id = UUID.randomUUID();
        long now = System.nanoTime() - 12_000_000_000L; // 12 s no passado, para terminar no "agora"
        t.register(id, "a", false, () -> 40, now);
        for (int i = 0; i < 240; i++) {
            now += 50_000_000L;
            t.tick(now);
        }
        BridgeProvider p = new BridgeProvider(t);
        assertEquals(ConnectionState.STABLE, p.connection(id));
        assertEquals(ConnectionReason.OK, p.status(id).reason());
    }
}
