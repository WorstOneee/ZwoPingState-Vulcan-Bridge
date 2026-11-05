package br.com.serpens.vulcanbridge.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Regras de evidência, imutáveis. Uma nova instância a cada /zvb reload (nova {@link #generation()},
 * o que descarta as evidências juntadas com as regras antigas).
 */
public final class EscalationPolicy {

    private static final AtomicLong GENERATIONS = new AtomicLong();

    /** Limite de min-flags por check (memória fixa por sessão). */
    public static final int MAX_MIN_FLAGS = 32;

    /** Regra pedida no config, antes de receber índices. */
    public record Spec(String key, String family, int minVl, int minFlags) {
    }

    private final List<CheckRule> rules;
    private final List<String> families;
    /** nome -> [0] = regra para todos os tipos, [1..26] = regra do tipo 'a'..'z'. */
    private final Map<String, CheckRule[]> byName;
    private final long windowMillis;
    private final int minFamilies;
    private final long generation;
    private final List<String> problems;

    private EscalationPolicy(List<CheckRule> rules, List<String> families, Map<String, CheckRule[]> byName,
                             long windowMillis, int minFamilies, List<String> problems) {
        this.rules = rules;
        this.families = families;
        this.byName = byName;
        this.windowMillis = windowMillis;
        this.minFamilies = minFamilies;
        this.problems = problems;
        this.generation = GENERATIONS.incrementAndGet();
    }

    /**
     * Monta a política. Entradas inválidas são ignoradas e descritas em {@link #problems()}.
     *
     * @param minFamilies mínimo 2: uma família só nunca basta
     */
    public static EscalationPolicy of(List<Spec> specs, long windowMillis, int minFamilies) {
        List<String> problems = new ArrayList<>();
        int families = Math.max(2, minFamilies);
        if (minFamilies < 2) {
            problems.add("min-families menor que 2 não é permitido; usando 2");
        }
        long window = Math.max(5_000L, windowMillis);

        List<CheckRule> rules = new ArrayList<>();
        Map<String, Integer> familyIndex = new LinkedHashMap<>();
        Map<String, CheckRule[]> byName = new HashMap<>();
        for (Spec spec : specs) {
            String key = spec.key() == null ? "" : spec.key().trim().toLowerCase(Locale.ROOT);
            String name = key;
            char type = 0;
            int colon = key.indexOf(':');
            if (colon >= 0) {
                name = key.substring(0, colon).trim();
                String t = key.substring(colon + 1).trim();
                if (t.length() != 1 || t.charAt(0) < 'a' || t.charAt(0) > 'z') {
                    problems.add("check '" + spec.key() + "' ignorado: o tipo deve ser uma letra (ex.: speed:b)");
                    continue;
                }
                type = t.charAt(0);
            }
            if (name.isEmpty() || !name.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                problems.add("check '" + spec.key() + "' ignorado: nome inválido");
                continue;
            }
            if (spec.minVl() < 1) {
                problems.add("check '" + spec.key() + "' ignorado: min-vl precisa ser 1 ou mais");
                continue;
            }
            CheckRule[] slots = byName.computeIfAbsent(name, n -> new CheckRule[27]);
            int slot = type == 0 ? 0 : type - 'a' + 1;
            if (slots[slot] != null) {
                problems.add("check '" + spec.key() + "' repetido; mantida a primeira entrada");
                continue;
            }
            String family = spec.family() == null || spec.family().isBlank()
                    ? name : spec.family().trim().toLowerCase(Locale.ROOT);
            int fi = familyIndex.computeIfAbsent(family, f -> familyIndex.size());
            int minFlags = Math.max(1, spec.minFlags());
            if (minFlags > MAX_MIN_FLAGS) {
                problems.add("check '" + spec.key() + "': min-flags limitado a " + MAX_MIN_FLAGS);
                minFlags = MAX_MIN_FLAGS;
            }
            CheckRule rule = new CheckRule(rules.size(), name, type, family, fi, spec.minVl(), minFlags);
            rules.add(rule);
            slots[slot] = rule;
        }
        if (!rules.isEmpty() && familyIndex.size() < families) {
            problems.add("só " + familyIndex.size() + " família(s) aprovada(s); são necessárias " + families
                    + " para haver ocorrência");
        }
        return new EscalationPolicy(List.copyOf(rules), List.copyOf(familyIndex.keySet()), byName, window, families,
                List.copyOf(problems));
    }

    public static EscalationPolicy empty() {
        return of(List.of(), 120_000L, 2);
    }

    /**
     * Regra que se aplica à flag, ou {@code null}. O(1), sem alocação quando o nome do Vulcan já
     * vem em minúsculas (o normal).
     */
    public CheckRule match(String checkName, char checkType) {
        if (checkName == null || byName.isEmpty()) {
            return null;
        }
        CheckRule[] slots = byName.get(checkName);
        if (slots == null) {
            slots = byName.get(checkName.toLowerCase(Locale.ROOT));
            if (slots == null) {
                return null;
            }
        }
        char t = Character.toLowerCase(checkType);
        if (t >= 'a' && t <= 'z') {
            CheckRule specific = slots[t - 'a' + 1];
            if (specific != null) {
                return specific;
            }
        }
        return slots[0];
    }

    /** {@code true} se for possível juntar uma ocorrência com as regras atuais. */
    public boolean canTrigger() {
        return !rules.isEmpty() && families.size() >= minFamilies;
    }

    public List<CheckRule> rules() { return rules; }
    public List<String> families() { return families; }
    public long windowMillis() { return windowMillis; }
    public int minFamilies() { return minFamilies; }
    public long generation() { return generation; }
    public List<String> problems() { return problems; }
}
