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
 * Lê o config.yml do Vulcan (só na inicialização e no /zvb reload, nunca por evento) e lista os
 * comandos configurados que podem banir por IP, para avisar a staff. A ponte não altera o config
 * do Vulcan.
 */
final class VulcanCommandScanner {

    private VulcanCommandScanner() {
    }

    /** Comandos de ban por IP encontrados em listas "*commands" do Vulcan. Vazio se não achar o arquivo. */
    static List<String> ipBanCommands(Plugin vulcan) {
        if (vulcan == null) {
            return List.of();
        }
        File file = new File(vulcan.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return List.of();
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        List<String> commands = new ArrayList<>();
        collect(y, commands);
        return BanCommandGuard.ipBanCommands(commands);
    }

    private static void collect(ConfigurationSection section, List<String> out) {
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                collect(section.getConfigurationSection(key), out);
            } else if (key.toLowerCase(Locale.ROOT).contains("commands") && section.isList(key)) {
                out.addAll(section.getStringList(key));
            }
        }
    }
}
