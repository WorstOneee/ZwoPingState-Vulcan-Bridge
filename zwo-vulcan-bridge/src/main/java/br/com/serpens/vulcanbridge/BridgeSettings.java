package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.BridgeMode;
import br.com.serpens.vulcanbridge.core.DurationText;
import br.com.serpens.vulcanbridge.core.EscalationPolicy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Configuração imutável da ponte. Recarregada inteira no /zvb reload. */
record BridgeSettings(
        BridgeMode mode,
        EscalationPolicy policy,
        long validityMillis,
        long banDurationMillis,
        long banTimeoutMillis,
        String banReason,
        String actorName,
        String kickMessage1,
        String kickMessage2,
        long teleportGraceMillis,
        long respawnGraceMillis,
        long worldChangeGraceMillis,
        List<String> bedrockNamePrefixes,
        boolean assumeJavaWithoutFloodgate,
        boolean notifyStaff,
        boolean auditLog,
        boolean checkVulcanConfig,
        List<String> problems) {

    static BridgeSettings load(FileConfiguration c) {
        List<String> problems = new ArrayList<>();

        List<EscalationPolicy.Spec> specs = new ArrayList<>();
        ConfigurationSection checks = c.getConfigurationSection("evidence.checks");
        if (checks != null) {
            for (String key : checks.getKeys(false)) {
                ConfigurationSection s = checks.getConfigurationSection(key);
                if (s == null) {
                    problems.add("evidence.checks." + key + " precisa ter min-vl (ex.: { min-vl: 10 })");
                    continue;
                }
                specs.add(new EscalationPolicy.Spec(key, s.getString("family", ""), s.getInt("min-vl", 0),
                        s.getInt("min-flags", 2)));
            }
        }
        EscalationPolicy policy = EscalationPolicy.of(specs,
                c.getLong("evidence.window-seconds", 120) * 1000L,
                c.getInt("evidence.min-families", 2));
        problems.addAll(policy.problems());

        long validity = duration(c, "sequence.validity", "7d", problems);
        long ban = duration(c, "sequence.ban-duration", "24h", problems);

        return new BridgeSettings(
                BridgeMode.parse(c.getString("mode", "OBSERVE")),
                policy,
                validity,
                ban,
                Math.max(5, c.getLong("sequence.ban-timeout-seconds", 30)) * 1000L,
                c.getString("sequence.ban-reason", "Uso de trapaça detectado pelo anticheat (3ª ocorrência)"),
                c.getString("sequence.actor", "ZwoVulcanBridge"),
                c.getString("messages.kick-1", "&cVocê foi desconectado pelo anticheat.\n&7Comportamento irregular detectado. Na próxima vez, nova desconexão; depois, ban temporário."),
                c.getString("messages.kick-2", "&cVocê foi desconectado pelo anticheat pela 2ª vez.\n&7Na próxima ocorrência você será banido temporariamente."),
                Math.max(0, c.getLong("protections.teleport-seconds", 3)) * 1000L,
                Math.max(0, c.getLong("protections.respawn-seconds", 3)) * 1000L,
                Math.max(0, c.getLong("protections.world-change-seconds", 3)) * 1000L,
                c.getStringList("bedrock.name-prefixes"),
                c.getBoolean("bedrock.assume-java-without-floodgate", false),
                c.getBoolean("alerts.notify-staff", true),
                c.getBoolean("alerts.audit-log", true),
                c.getBoolean("alerts.check-vulcan-config", true),
                List.copyOf(problems));
    }

    private static long duration(FileConfiguration c, String path, String def, List<String> problems) {
        String raw = c.getString(path, def);
        long v = DurationText.parse(raw);
        if (v <= 0) {
            problems.add(path + " inválido ('" + raw + "'); usando " + def);
            v = DurationText.parse(def);
        }
        return v;
    }
}
