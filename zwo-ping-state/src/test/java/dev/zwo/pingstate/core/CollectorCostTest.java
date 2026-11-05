package dev.zwo.pingstate.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Medição grosseira do custo do núcleo da coleta e da consulta, fora do servidor.
 * Não substitui o Spark (não inclui o Player#getPing real nem o scheduler do Paper),
 * mas dá a ordem de grandeza. Os números saem no relatório do teste (stdout).
 */
class CollectorCostTest {

    @Test
    @DisplayName("custo do tick com 100 e 500 jogadores simulados e custo da consulta")
    void measure() {
        for (int players : new int[]{100, 500}) {
            PingTracker t = new PingTracker(PingSettings.defaults(), System::nanoTime);
            UUID[] ids = new UUID[players];
            long fake = 0;
            for (int i = 0; i < players; i++) {
                ids[i] = UUID.randomUUID();
                t.register(ids[i], "p" + i, false, () -> 30 + ThreadLocalRandom.current().nextInt(10), fake);
            }
            // aquecimento do JIT
            for (int i = 0; i < 40_000; i++) {
                fake += 50_000_000L;
                t.tick(fake);
            }
            int ticks = 20_000;
            long start = System.nanoTime();
            for (int i = 0; i < ticks; i++) {
                fake += 50_000_000L;
                t.tick(fake);
            }
            double perTickUs = (System.nanoTime() - start) / 1000.0 / ticks;

            int queries = 2_000_000;
            long sink = 0;
            long qs = System.nanoTime();
            for (int i = 0; i < queries; i++) {
                sink += t.query(ids[i % players], fake).state().ordinal();
            }
            double perQueryNs = (double) (System.nanoTime() - qs) / queries;

            System.out.printf("[custo] %d jogadores: %.2f µs por tick (lote de %d), consulta %.1f ns (sink %d)%n",
                    players, perTickUs, t.currentBatchSize(), perQueryNs, sink % 2);
            assertTrue(perTickUs < 1000, "tick deveria ficar muito abaixo de 1 ms");
        }
    }
}
