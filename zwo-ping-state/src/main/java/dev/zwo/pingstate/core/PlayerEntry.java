package dev.zwo.pingstate.core;

import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Dados de um jogador online. Criado no join, liberado no quit.
 *
 * <p>Somente {@link #snapshot} é lido fora da thread principal (campo volátil). Todo o resto
 * é tocado apenas pela coleta, na thread principal.</p>
 */
public final class PlayerEntry {

    final UUID id;
    final String name;
    final boolean bedrock;
    final long joinedAtNanos;
    final IntSupplier pingSource;
    final PingWindow window;

    int index;                 // posição na lista de rodízio
    long lastSampleNanos;      // 0 = nunca amostrado
    long lastSampleTick;       // tick da coleta em que foi lido (0 = nunca)
    long goodSinceNanos = -1;  // início do período bom contínuo; -1 = não está bom
    boolean hadInstability;    // já ficou instável nesta sessão -> exige recuperação

    volatile PingSnapshot snapshot;

    PlayerEntry(UUID id, String name, boolean bedrock, IntSupplier pingSource, int windowSize, long nowNanos) {
        this.id = id;
        this.name = name;
        this.bedrock = bedrock;
        this.joinedAtNanos = nowNanos;
        this.pingSource = pingSource;
        this.window = new PingWindow(windowSize);
        this.snapshot = PingSnapshot.initial(nowNanos);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public boolean bedrock() { return bedrock; }
    public long joinedAtNanos() { return joinedAtNanos; }
    public PingSnapshot snapshot() { return snapshot; }
}
