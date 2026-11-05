package br.com.serpens.vulcanbridge;

import dev.zwo.modpanel.api.ProfileBanApi;
import dev.zwo.modpanel.api.ProfileBanRequest;
import dev.zwo.modpanel.api.ProfileBanResult;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Única classe da ponte que usa as classes do ModPanel ({@code dev.zwo.modpanel.api}). Só é
 * carregada depois que {@link BanLink} confirmou que a API existe.
 */
final class ModPanelBackend implements BanBackend {

    /** Versão do contrato que esta ponte conhece. */
    static final int EXPECTED_API = 1;

    /** Resultado da procura: um backend pronto ou o motivo de não haver. */
    record Lookup(ModPanelBackend backend, BanLink.State state, String reason) {
    }

    private final ProfileBanApi api;
    private final Plugin owner;

    private ModPanelBackend(ProfileBanApi api, Plugin owner) {
        this.api = api;
        this.owner = owner;
    }

    static Lookup lookup() {
        RegisteredServiceProvider<ProfileBanApi> rsp = Bukkit.getServicesManager().getRegistration(ProfileBanApi.class);
        if (rsp == null) {
            return new Lookup(null, BanLink.State.MISSING, "ModPanel ligado, mas a ProfileBanApi não está registrada");
        }
        int version = rsp.getProvider().apiVersion();
        if (version != EXPECTED_API) {
            return new Lookup(null, BanLink.State.INCOMPATIBLE, "ProfileBanApi versão " + version
                    + " (esta ponte usa a versão " + EXPECTED_API + ")");
        }
        return new Lookup(new ModPanelBackend(rsp.getProvider(), rsp.getPlugin()), BanLink.State.READY, "");
    }

    @Override
    public String describe() {
        return owner.getName() + " " + owner.getPluginMeta().getVersion() + " (ProfileBanApi v" + EXPECTED_API + ")";
    }

    @Override
    public CompletableFuture<Outcome> tempBanProfile(UUID uuid, String name, Duration duration, String reason,
                                                     String actor, String requestId) {
        CompletableFuture<ProfileBanResult> future;
        try {
            future = api.tempBanProfile(new ProfileBanRequest(uuid, name, duration, reason, actor, requestId));
        } catch (Throwable t) {
            // LinkageError (API trocada em tempo de execução) ou erro do ModPanel: nunca "sucesso".
            return CompletableFuture.completedFuture(Outcome.uncertain("chamada à ProfileBanApi falhou: " + t));
        }
        if (future == null) {
            return CompletableFuture.completedFuture(Outcome.uncertain("ProfileBanApi devolveu null"));
        }
        return future.handle((r, error) -> {
            if (error != null || r == null) {
                return Outcome.uncertain("ProfileBanApi sem resultado: " + error);
            }
            boolean confirmed = r.status() == ProfileBanResult.Status.APPLIED && r.verified() && !r.ipBanned()
                    && uuid.equals(r.uuid()) && r.punishmentId() > 0;
            return new Outcome(confirmed, r.status().name(), r.punishmentId(), r.expiresAt(),
                    r.message() + (r.verified() ? "" : " (não conferido)"));
        });
    }
}
