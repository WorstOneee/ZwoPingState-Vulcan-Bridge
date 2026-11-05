package br.com.serpens.vulcanbridge;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Quem aplica o ban temporário da 3ª ocorrência. Só tipos do JDK aqui, para que a ponte carregue
 * mesmo sem o ModPanel instalado (as classes do ModPanel ficam isoladas em {@link ModPanelBackend}).
 */
interface BanBackend {

    /**
     * @param confirmed    o ModPanel criou o registro agora e conferiu banco + banlist, sem IP
     * @param status       status devolvido pelo ModPanel (APPLIED, ALREADY_BANNED, REJECTED, FAILED...)
     * @param punishmentId id no histórico do ModPanel ({@code -1} se não houver)
     * @param detail       mensagem do ModPanel ou da ponte
     */
    record Outcome(boolean confirmed, String status, long punishmentId, Long expiresAt, String detail) {

        static Outcome uncertain(String detail) {
            return new Outcome(false, "INCERTO", -1L, null, detail);
        }
    }

    String describe();

    CompletableFuture<Outcome> tempBanProfile(UUID uuid, String name, Duration duration, String reason, String actor,
                                              String requestId);
}
