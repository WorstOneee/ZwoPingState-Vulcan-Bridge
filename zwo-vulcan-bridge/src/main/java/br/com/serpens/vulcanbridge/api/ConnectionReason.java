package br.com.serpens.vulcanbridge.api;

/** Motivo associado a um {@link ConnectionState}. Serve para diagnóstico e auditoria. */
public enum ConnectionReason {
    OK("conexão estável"),
    HIGH_PING("ping alto"),
    PING_JITTER("ping oscilando"),
    PING_SPIKES("picos de ping"),
    SERVER_UNSTABLE("servidor instável"),
    RECOVERING("em recuperação após instabilidade"),
    INSUFFICIENT_DATA("dados insuficientes"),
    STALE_DATA("dados vencidos"),
    NOT_TRACKED("jogador não monitorado"),
    BEDROCK_PROXY("Bedrock: o ping visto pelo Paper é do proxy, não do cliente"),
    COLLECTION_ERROR("falha na coleta"),
    PROVIDER_UNAVAILABLE("provedor de estado indisponível"),
    PROVIDER_INCOMPATIBLE("provedor sem snapshot com revisões (versão antiga)"),
    UNSPECIFIED("motivo não informado pelo provedor");

    private final String description;

    ConnectionReason(String description) {
        this.description = description;
    }

    /** Descrição curta em PT-BR. */
    public String description() {
        return description;
    }
}
