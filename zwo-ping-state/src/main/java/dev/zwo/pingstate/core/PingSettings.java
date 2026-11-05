package dev.zwo.pingstate.core;

/**
 * Configuração imutável do ZwoPingState. Tempos já convertidos para nanossegundos.
 *
 * @param sampleIntervalTicks     cada jogador é amostrado uma vez a cada N ticks (20 = 1 s)
 * @param maxSamplesPerTick       orçamento fixo: máximo de jogadores lidos por tick
 * @param maxCollectorNanosPerTick orçamento de tempo por tick (o lote para se estourar)
 * @param windowSize              tamanho da janela circular por jogador
 * @param minSamples              amostras válidas mínimas antes de responder STABLE
 * @param maxPing                 última amostra acima disso = ping alto
 * @param maxAveragePing          média da janela acima disso = ping alto
 * @param maxJitter               média da variação entre amostras consecutivas acima disso = oscilando
 * @param maxRange                amplitude da janela (máx - mín) acima disso = oscilando
 * @param spikeJump               salto entre amostras consecutivas que conta como pico
 * @param spikeAbsolute           amostra acima disso conta como pico
 * @param maxSpikes               picos tolerados na janela
 * @param recoveryNanos           período contínuo bom exigido para voltar a STABLE
 * @param staleNanos              idade máxima da última avaliação; acima disso = UNKNOWN
 * @param resetGapNanos           intervalo sem amostra que zera a janela (descontinuidade)
 * @param zeroPingGraceNanos      após o join, ping 0 é tratado como "sem dado" durante esse tempo
 * @param minTps                  TPS (curto, medido pelo próprio coletor) abaixo disso = servidor instável
 * @param maxMspt                 MSPT médio (Paper) acima disso = servidor instável
 * @param stallNanos              um único tick mais longo que isso = travada
 * @param serverRecoveryNanos     período contínuo estável do servidor exigido após instabilidade
 * @param bedrockUnknown          jogadores Bedrock respondem UNKNOWN (o ping é do proxy)
 */
public record PingSettings(
        int sampleIntervalTicks,
        int maxSamplesPerTick,
        long maxCollectorNanosPerTick,
        int windowSize,
        int minSamples,
        int maxPing,
        int maxAveragePing,
        int maxJitter,
        int maxRange,
        int spikeJump,
        int spikeAbsolute,
        int maxSpikes,
        long recoveryNanos,
        long staleNanos,
        long resetGapNanos,
        long zeroPingGraceNanos,
        double minTps,
        double maxMspt,
        long stallNanos,
        long serverRecoveryNanos,
        boolean bedrockUnknown) {

    public static final int MAX_WINDOW = 120;

    public PingSettings {
        sampleIntervalTicks = clamp(sampleIntervalTicks, 1, 200);
        maxSamplesPerTick = clamp(maxSamplesPerTick, 1, 10_000);
        maxCollectorNanosPerTick = Math.max(50_000L, maxCollectorNanosPerTick);
        windowSize = clamp(windowSize, 3, MAX_WINDOW);
        minSamples = clamp(minSamples, 2, windowSize);
        maxSpikes = Math.max(0, maxSpikes);
        staleNanos = Math.max(1_000_000_000L, staleNanos);
        resetGapNanos = Math.max(staleNanos, resetGapNanos);
    }

    public static PingSettings defaults() {
        return new PingSettings(
                20, 32, 1_000_000L,
                20, 8,
                250, 200, 25, 60, 80, 400, 0,
                seconds(10), seconds(5), seconds(5), seconds(20),
                18.0, 50.0, ms(250), seconds(10),
                true);
    }

    public static long seconds(double s) {
        return (long) (s * 1_000_000_000L);
    }

    public static long ms(double ms) {
        return (long) (ms * 1_000_000L);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
