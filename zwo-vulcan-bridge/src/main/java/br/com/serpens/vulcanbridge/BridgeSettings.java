package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.BanCommandGuard;
import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.FlagPolicy;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Configuração imutável da ponte. Recarregada inteira no /zvb reload. */
record BridgeSettings(
        BridgeMode mode,
        boolean cancelVulcanPunishments,
        boolean usePostFlagEvent,
        FlagPolicy policy,
        long teleportGraceMillis,
        long respawnGraceMillis,
        long worldChangeGraceMillis,
        int kicksBeforeBan,
        long strikeExpireMillis,
        String kickMessage,
        String banCommand,
        String banReason,
        BanCommandGuard.Verdict banVerdict,
        String pendingBanKickMessage,
        String banKickMessage,
        List<String> bedrockNamePrefixes,
        boolean alertStaff,
        boolean auditLog) {

    static BridgeSettings load(FileConfiguration c) {
        Set<String> ignoredChecks = lower(c.getStringList("flags.ignored-checks"));
        Set<String> ignoredCategories = lower(c.getStringList("flags.ignored-categories"));
        boolean excludeBedrock = c.getBoolean("bedrock.exclude-from-punishments", true);

        FlagPolicy d = FlagPolicy.defaults();
        FlagPolicy policy = new FlagPolicy(
                c.getDouble("flags.min-vl-ratio", d.minVlRatio()),
                c.getInt("flags.min-vl", d.minVl()),
                c.getInt("flags.required-high-flags", d.requiredHighFlags()),
                c.getLong("flags.window-seconds", d.windowMillis() / 1000L) * 1000L,
                c.getBoolean("flags.only-punishable-checks", true),
                excludeBedrock,
                ignoredChecks,
                ignoredCategories);

        String banCommand = c.getString("punishments.ban-command", "");
        return new BridgeSettings(
                BridgeMode.parse(c.getString("mode", "OBSERVE")),
                c.getBoolean("vulcan.cancel-vulcan-punishments", true),
                "POST_FLAG".equalsIgnoreCase(c.getString("vulcan.listen-event", "FLAG")),
                policy,
                Math.max(0, c.getLong("protections.teleport-seconds", 3)) * 1000L,
                Math.max(0, c.getLong("protections.respawn-seconds", 3)) * 1000L,
                Math.max(0, c.getLong("protections.world-change-seconds", 3)) * 1000L,
                Math.max(1, c.getInt("punishments.kicks-before-ban", 2)),
                Math.max(0, c.getLong("punishments.strike-expire-days", 30)) * 86_400_000L,
                c.getString("punishments.kick-message",
                        "&cVocê foi desconectado pelo anticheat.\n&7Se acredita que foi um erro, fale com a staff."),
                banCommand == null ? "" : banCommand.trim(),
                c.getString("punishments.ban-reason", "Anticheat: {check}"),
                BanCommandGuard.check(banCommand),
                c.getString("punishments.pending-ban-kick-message",
                        "&cVocê foi desconectado pelo anticheat.\n&7Seu caso foi enviado para revisão da staff."),
                c.getString("punishments.ban-kick-message",
                        "&cVocê foi banido pelo anticheat.\n&7Para recorrer, procure a staff."),
                c.getStringList("bedrock.name-prefixes"),
                c.getBoolean("alerts.notify-staff", true),
                c.getBoolean("alerts.audit-log", true));
    }

    private static Set<String> lower(List<String> raw) {
        Set<String> out = new HashSet<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
