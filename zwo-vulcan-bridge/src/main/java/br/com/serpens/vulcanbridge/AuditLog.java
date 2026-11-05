package br.com.serpens.vulcanbridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * {@code plugins/ZwoVulcanBridge/punicoes.log}: uma linha por PUNIÇÃO do Vulcan (liberada ou
 * bloqueada). Nunca por flag. Escrita numa única thread própria, nunca na thread do servidor.
 */
final class AuditLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File file;
    private final Logger logger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ZwoVulcanBridge-IO");
        t.setDaemon(true);
        return t;
    });

    AuditLog(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "punicoes.log");
        this.logger = logger;
    }

    void write(String line) {
        String stamped = "[" + LocalDateTime.now().format(TS) + "] " + line + System.lineSeparator();
        io.execute(() -> {
            try {
                Files.writeString(file.toPath(), stamped, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warning("Falha ao gravar punicoes.log: " + e.getMessage());
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
