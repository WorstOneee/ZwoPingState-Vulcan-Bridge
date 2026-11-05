package br.com.serpens.vulcanbridge.api;

/**
 * Estado da qualidade de conexão de um jogador, do ponto de vista da ponte.
 *
 * <p>Somente {@link #STABLE} permite que a ponte avalie as flags do Vulcan. Qualquer outro
 * estado faz a ponte descartar a sequência de flags do jogador.</p>
 */
public enum ConnectionState {
    /** Há amostras recentes e suficientes, todas dentro dos limites configurados. */
    STABLE,
    /** Houve sinais de instabilidade (ping alto, oscilação, servidor instável ou recuperação). */
    UNSTABLE,
    /** Faltam dados, os dados venceram ou houve falha na coleta. */
    UNKNOWN;

    /** {@code true} apenas para {@link #STABLE}. */
    public boolean allowsEvaluation() {
        return this == STABLE;
    }
}
