package br.com.serpens.vulcanbridge.core;

import java.util.UUID;

/**
 * Histórico de ocorrências de um UUID (persistido em {@code historico.yml}). Imutável.
 *
 * @param kicks         kicks confirmados (a desconexão aconteceu pela ação da ponte)
 * @param firstKickAt   instante do primeiro kick; a validade conta a partir dele
 * @param lastSessionId sessão em que o último kick foi contado (nunca duas na mesma sessão)
 * @param ban           estado do ban temporário da 3ª ocorrência
 * @param banRequestId  id enviado ao ModPanel (o mesmo id nunca gera dois registros lá)
 * @param banDetail     resposta do ModPanel ou motivo da revisão
 */
public record OffenderRecord(UUID playerId, String name, int kicks, long firstKickAt, long lastKickAt,
                             long lastSessionId, BanState ban, long banAt, String banRequestId, String banDetail) {

    public enum BanState {
        NONE("nenhum"),
        /** Pedido enviado ao ModPanel, sem resposta ainda. Num reinício vira REVIEW. */
        PENDING("pedido em andamento"),
        /** O ModPanel confirmou (registro ativo no banco e na banlist). */
        APPLIED("aplicado e conferido"),
        /** Falhou ou ficou incerto: a staff decide; a ponte não tenta outro ban. */
        REVIEW("para revisão");

        private final String description;

        BanState(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public OffenderRecord {
        name = name == null ? "" : name;
        ban = ban == null ? BanState.NONE : ban;
        banRequestId = banRequestId == null ? "" : banRequestId;
        banDetail = banDetail == null ? "" : banDetail;
    }

    public static OffenderRecord fresh(UUID playerId, String name) {
        return new OffenderRecord(playerId, name, 0, 0L, 0L, 0L, BanState.NONE, 0L, "", "");
    }

    /** Vencido: passou a validade desde o primeiro kick. */
    public boolean expired(long now, long validityMillis) {
        return kicks > 0 && now - firstKickAt >= validityMillis;
    }

    public OffenderRecord withKick(long sessionId, long now, String currentName) {
        return new OffenderRecord(playerId, currentName, kicks + 1, kicks == 0 ? now : firstKickAt, now,
                sessionId, ban, banAt, banRequestId, banDetail);
    }

    public OffenderRecord withBan(BanState state, long now, String requestId, String detail) {
        return new OffenderRecord(playerId, name, kicks, firstKickAt, lastKickAt, lastSessionId, state, now,
                requestId, detail);
    }
}
