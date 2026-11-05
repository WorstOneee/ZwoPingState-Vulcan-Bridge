package dev.zwo.pingstate.core;

/**
 * Janela circular de tamanho fixo com as últimas amostras de ping (ms) de um jogador.
 * Só guarda inteiros; não aloca depois de criada. Acessada apenas na thread principal.
 */
public final class PingWindow {

    private final int[] values = new int[PingSettings.MAX_WINDOW];
    private int capacity;
    private int head;  // próxima posição de escrita
    private int count;

    public PingWindow(int capacity) {
        resize(capacity);
    }

    /** Muda a capacidade (reload). Descarta as amostras. */
    public void resize(int capacity) {
        this.capacity = Math.max(1, Math.min(PingSettings.MAX_WINDOW, capacity));
        clear();
    }

    public void clear() {
        head = 0;
        count = 0;
    }

    public void add(int ping) {
        values[head] = ping;
        head = (head + 1) % capacity;
        if (count < capacity) {
            count++;
        }
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    /** i = 0 é a amostra mais antiga. */
    int get(int i) {
        int start = (head - count + capacity) % capacity;
        return values[(start + i) % capacity];
    }

    /**
     * Calcula as métricas da janela (O(tamanho da janela), executado apenas na coleta).
     * Escreve em {@code out} para não alocar.
     */
    public void compute(int spikeJump, int spikeAbsolute, WindowStats out) {
        out.reset();
        if (count == 0) {
            return;
        }
        int start = (head - count + capacity) % capacity;
        long sum = 0;
        long jitterSum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int spikes = 0;
        int prev = 0;
        for (int i = 0; i < count; i++) {
            int v = values[(start + i) % capacity];
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            boolean spike = v >= spikeAbsolute;
            if (i > 0) {
                int diff = Math.abs(v - prev);
                jitterSum += diff;
                if (diff >= spikeJump) {
                    spike = true;
                }
            }
            if (spike) {
                spikes++;
            }
            prev = v;
        }
        out.samples = count;
        out.last = prev;
        out.min = min;
        out.max = max;
        out.average = (int) Math.round((double) sum / count);
        out.jitter = count > 1 ? (int) Math.round((double) jitterSum / (count - 1)) : 0;
        out.spikes = spikes;
    }
}
