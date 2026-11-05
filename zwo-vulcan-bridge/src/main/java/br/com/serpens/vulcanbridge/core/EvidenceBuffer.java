package br.com.serpens.vulcanbridge.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Evidências de UMA sessão: para cada check aprovado, um anel com as últimas {@code min-flags}
 * flags (instante e VL). Basta isso para decidir "esse check repetiu min-flags vezes dentro da
 * janela", então a memória é fixa (soma dos min-flags, no máximo
 * {@link EscalationPolicy#MAX_MIN_FLAGS} por check) e um check que dispara muito nunca empurra
 * as flags de outro check para fora.
 *
 * <p>Não é thread-safe: o dono ({@link PlayerSession}) sincroniza. Sem alocação ao adicionar; os
 * anéis só são (re)criados quando a política muda.</p>
 */
final class EvidenceBuffer {

    private long generation = -1;
    private long[][] at = new long[0][];
    private int[][] vl = new int[0][];
    private int[] head = new int[0];
    private int[] count = new int[0];
    private int total;

    private void ensure(EscalationPolicy policy) {
        if (policy.generation() == generation) {
            return;
        }
        List<CheckRule> rules = policy.rules();
        at = new long[rules.size()][];
        vl = new int[rules.size()][];
        for (CheckRule r : rules) {
            at[r.index()] = new long[r.minFlags()];
            vl[r.index()] = new int[r.minFlags()];
        }
        head = new int[rules.size()];
        count = new int[rules.size()];
        total = 0;
        generation = policy.generation();
    }

    void clear() {
        for (int i = 0; i < count.length; i++) {
            count[i] = 0;
            head[i] = 0;
        }
        total = 0;
    }

    /** Flags guardadas (no máximo a soma dos min-flags). */
    int size() {
        return total;
    }

    void add(CheckRule rule, int flagVl, long now, EscalationPolicy policy) {
        ensure(policy);
        int r = rule.index();
        long[] ring = at[r];
        ring[head[r]] = now;
        vl[r][head[r]] = flagVl;
        head[r] = (head[r] + 1) % ring.length;
        if (count[r] < ring.length) {
            count[r]++;
            total++;
        }
    }

    /** O check tem min-flags flags e a mais antiga delas ainda está dentro da janela. */
    private boolean satisfied(CheckRule rule, long oldestAllowed) {
        int r = rule.index();
        if (count[r] < at[r].length) {
            return false;
        }
        long oldest = at[r][head[r]]; // anel cheio: a próxima posição de escrita é a mais antiga
        return oldest >= oldestAllowed;
    }

    /** Quantas famílias já cumprem o mínimo. O(regras), sem alocação além de um vetor pequeno. */
    int satisfiedFamilies(EscalationPolicy policy, long now) {
        ensure(policy);
        long oldestAllowed = now - policy.windowMillis();
        boolean[] family = new boolean[policy.families().size()];
        int n = 0;
        for (CheckRule cr : policy.rules()) {
            if (!family[cr.familyIndex()] && satisfied(cr, oldestAllowed)) {
                family[cr.familyIndex()] = true;
                n++;
            }
        }
        return n;
    }

    /** Resumo legível (só ao disparar uma ocorrência). */
    String summary(EscalationPolicy policy, long now) {
        long oldestAllowed = now - policy.windowMillis();
        List<String> parts = new ArrayList<>();
        for (CheckRule cr : policy.rules()) {
            int r = cr.index();
            int inWindow = 0;
            int max = 0;
            for (int i = 0; i < count[r]; i++) {
                if (at[r][i] >= oldestAllowed) {
                    inWindow++;
                    max = Math.max(max, vl[r][i]);
                }
            }
            if (inWindow > 0) {
                parts.add(cr.key() + " x" + inWindow + " (VL até " + max + ", família " + cr.family() + ")");
            }
        }
        return String.join(", ", parts);
    }
}
