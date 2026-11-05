package br.com.serpens.vulcanbridge.core;

/** Por que a ponte deixou passar ou bloqueou uma flag/punição do Vulcan. */
public enum GateReason {
    ALLOWED("conexão estável"),
    OBSERVE_MODE("modo observação (teria passado)"),
    BEDROCK("jogador Bedrock (excluído de punições automáticas)"),
    PROTECTION("proteção de teleporte/respawn/troca de mundo"),
    PROVIDER_UNAVAILABLE("provedor de conexão indisponível"),
    CONNECTION_UNSTABLE("conexão instável"),
    CONNECTION_UNKNOWN("conexão desconhecida"),
    RECENT_LAG("flags recentes com lag (VL contaminado)"),
    NO_SESSION("jogador sem sessão (entrando/saindo)");

    private final String description;

    GateReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
