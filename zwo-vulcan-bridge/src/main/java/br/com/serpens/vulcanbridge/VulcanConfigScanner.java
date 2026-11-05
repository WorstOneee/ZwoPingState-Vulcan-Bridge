package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.BanCommandGuard;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lê o config.yml do Vulcan (só ao ligar e no /zvb reload) e avisa a staff sobre o que não deveria
 * estar ligado: checks com {@code punishable: true} (o Vulcan tentaria punir sozinho) e comandos
 * que banem IP. A ponte não altera o config do Vulcan.
 */
final class VulcanConfigScanner {

    record Report(boolean found, List<String> punishableChecks, List<String> ipBanCommands) {

        static final Report NONE = new Report(false, List.of(), List.of());
    }

    private VulcanConfigScanner() {
    }

    static Report scan(Plugin vulcan) {
        if (vulcan == null) {
            return Report.NONE;
        }
        File file = new File(vulcan.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return Report.NONE;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        List<String> commands = new ArrayList<>();
        collectCommands(y, commands);
        List<String> punishable = new ArrayList<>();
        ConfigurationSection checks = y.getConfigurationSection("checks");
        if (checks != null) {
            collectPunishable(checks, "", punishable);
        }
        return new Report(true, List.copyOf(punishable), BanCommandGuard.ipBanCommands(commands));
    }

    private static void collectCommands(ConfigurationSection section, List<String> out) {
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                collectCommands(section.getConfigurationSection(key), out);
            } else if (key.toLowerCase(Locale.ROOT).contains("commands") && section.isList(key)) {
                out.addAll(section.getStringList(key));
            }
        }
    }

    private static void collectPunishable(ConfigurationSection section, String path, List<String> out) {
        if (section.isBoolean("punishable")) {
            if (section.getBoolean("punishable") && section.getBoolean("enabled", true)) {
                out.add(path);
            }
            return;
        }
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                collectPunishable(section.getConfigurationSection(key), path.isEmpty() ? key : path + "." + key, out);
            }
        }
    }
}
