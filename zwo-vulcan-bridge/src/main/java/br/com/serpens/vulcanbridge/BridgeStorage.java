package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.Strike;
import br.com.serpens.vulcanbridge.core.StrikeLedger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gravação em disco da ponte: {@code strikes.yml} e {@code ocorrencias.log}.
 *
 * <p>Só é usada quando existe uma ocorrência confirmada (raro). Toda escrita acontece numa
 * única thread própria, nunca na thread do servidor e nunca por flag.</p>
 */
final class BridgeStorage {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File strikesFile;
    private final File auditFile;
    private final Logger logger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ZwoVulcanBridge-IO");
        t.setDaemon(true);
        return t;
    });

    BridgeStorage(File dataFolder, Logger logger) {
        this.strikesFile = new File(dataFolder, "strikes.yml");
        this.auditFile = new File(dataFolder, "ocorrencias.log");
        this.logger = logger;
    }

    /** Leitura síncrona: só no onEnable. */
    Map<UUID, List<Strike>> loadStrikes() {
        Map<UUID, List<Strike>> out = new HashMap<>();
        if (!strikesFile.exists()) {
            return out;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(strikesFile);
        ConfigurationSection players = y.getConfigurationSection("players");
        if (players == null) {
            return out;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                List<Strike> list = new ArrayList<>();
                for (String line : players.getStringList(key)) {
                    try {
                        list.add(Strike.parse(line));
                    } catch (RuntimeException e) {
                        logger.warning("Strike ignorado em strikes.yml (" + key + "): " + e.getMessage());
                    }
                }
                if (!list.isEmpty()) {
                    out.put(id, list);
                }
            } catch (IllegalArgumentException e) {
                logger.warning("UUID inválido em strikes.yml: " + key);
            }
        }
        return out;
    }

    void saveStrikesAsync(StrikeLedger ledger) {
        Map<UUID, List<Strike>> snapshot = ledger.snapshot();
        io.execute(() -> writeStrikes(snapshot));
    }

    void saveStrikesSync(StrikeLedger ledger) {
        writeStrikes(ledger.snapshot());
    }

    private void writeStrikes(Map<UUID, List<Strike>> snapshot) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "ZwoVulcanBridge - histórico de ocorrências por perfil (UUID).",
                "Formato: timestamp|sessão|ação|modo|check. Não edite com o servidor ligado."));
        snapshot.forEach((id, list) -> {
            List<String> lines = new ArrayList<>(list.size());
            for (Strike s : list) {
                lines.add(s.serialize());
            }
            y.set("players." + id, lines);
        });
        try {
            File tmp = new File(strikesFile.getParentFile(), "strikes.yml.tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), strikesFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                y.save(strikesFile);
            } catch (IOException e2) {
                logger.log(Level.SEVERE, "Falha ao salvar strikes.yml", e2);
            }
        }
    }

    void audit(String line) {
        String stamped = "[" + LocalDateTime.now().format(TS) + "] " + line + System.lineSeparator();
        io.execute(() -> {
            try {
                Files.writeString(auditFile.toPath(), stamped, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warning("Falha ao gravar ocorrencias.log: " + e.getMessage());
            }
        });
    }

    void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
