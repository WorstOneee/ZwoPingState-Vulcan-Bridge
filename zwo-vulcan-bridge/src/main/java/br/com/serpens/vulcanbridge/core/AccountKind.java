package br.com.serpens.vulcanbridge.core;

/** Tipo da conta, decidido uma vez no join. */
public enum AccountKind {
    /** Java confirmado pela API do Floodgate (e UUID/nome sem sinais de Bedrock). */
    JAVA("Java (confirmado)"),
    /** Bedrock (Geyser/Floodgate): fora da automação. */
    BEDROCK("Bedrock"),
    /** Não deu para confirmar que é Java (Floodgate ausente ou falhou): só registro para revisão. */
    UNCERTAIN("incerto (só revisão)");

    private final String description;

    AccountKind(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
