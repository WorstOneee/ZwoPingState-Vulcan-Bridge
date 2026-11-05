package dev.zwo.pingstate.core;

/**
 * Motivos do estado. Os nomes batem com {@code ConnectionReason} da ponte, para o adaptador
 * fazer o mapeamento por nome uma única vez.
 */
public enum PingReason {
    OK("conexão estável"),
    HIGH_PING("ping alto"),
    PING_JITTER("ping oscilando"),
    PING_SPIKES("picos de ping"),
    SERVER_UNSTABLE("servidor instável"),
    RECOVERING("em recuperação após instabilidade"),
    INSUFFICIENT_DATA("dados insuficientes"),
    STALE_DATA("dados vencidos"),
    NOT_TRACKED("jogador não monitorado"),
    BEDROCK_PROXY("Bedrock: ping do proxy, não do cliente"),
    COLLECTION_ERROR("falha na coleta");

    private final String description;

    PingReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
