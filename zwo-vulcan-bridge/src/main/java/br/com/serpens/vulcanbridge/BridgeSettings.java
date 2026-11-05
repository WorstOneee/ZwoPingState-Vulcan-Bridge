package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.Gate;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** Configuração imutável da ponte. Recarregada inteira no /zvb reload. */
record BridgeSettings(
        Gate.Policy policy,
        long teleportGraceMillis,
        long respawnGraceMillis,
        long worldChangeGraceMillis,
        List<String> bedrockNamePrefixes,
        boolean notifyStaff,
        boolean auditLog,
        boolean checkVulcanCommands,
        boolean resetVlWhenBlocked) {

    BridgeMode mode() {
        return policy.mode();
    }

    static BridgeSettings load(FileConfiguration c) {
        Gate.Policy policy = new Gate.Policy(
                BridgeMode.parse(c.getString("mode", "OBSERVE")),
                c.getBoolean("filter.cancel-flags", false),
                c.getBoolean("bedrock.exclude-from-punishments", true),
                Math.max(0, c.getLong("filter.recent-lag-seconds", 120)) * 1000L);
        return new BridgeSettings(
                policy,
                Math.max(0, c.getLong("protections.teleport-seconds", 3)) * 1000L,
                Math.max(0, c.getLong("protections.respawn-seconds", 3)) * 1000L,
                Math.max(0, c.getLong("protections.world-change-seconds", 3)) * 1000L,
                c.getStringList("bedrock.name-prefixes"),
                c.getBoolean("alerts.notify-staff", true),
                c.getBoolean("alerts.audit-log", true),
                c.getBoolean("alerts.check-vulcan-commands", true),
                c.getBoolean("filter.reset-vl-when-blocked", true));
    }
}
