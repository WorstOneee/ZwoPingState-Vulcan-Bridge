package br.com.serpens.vulcanbridge.core;

/** Próximo passo da sequência por UUID: kick, kick, ban temporário. */
public final class Ladder {

    public enum Step {
        KICK_1("1º kick"),
        KICK_2("2º kick"),
        TEMPBAN("ban temporário"),
        /** Nada automático: só registro para a staff. */
        REVIEW_ONLY("só registro para revisão");

        private final String description;

        Step(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private Ladder() {
    }

    /**
     * @param record    histórico atual do UUID ({@code null} = nenhum)
     * @param sessionId sessão em que a ocorrência aconteceu
     */
    public static Step next(OffenderRecord record, long now, long validityMillis, long sessionId) {
        if (record == null || record.expired(now, validityMillis)) {
            return Step.KICK_1; // sem histórico válido: começa do zero
        }
        if (record.ban() != OffenderRecord.BanState.NONE) {
            // Ban já aplicado, em andamento ou em revisão: a ponte não tenta outro.
            return Step.REVIEW_ONLY;
        }
        if (record.kicks() == 0) {
            return Step.KICK_1;
        }
        if (record.lastSessionId() == sessionId) {
            return Step.REVIEW_ONLY; // nunca duas ocorrências na mesma sessão
        }
        return record.kicks() == 1 ? Step.KICK_2 : Step.TEMPBAN;
    }
}
