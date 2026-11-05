package br.com.serpens.vulcanbridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * {@code plugins/ZwoVulcanBridge/decisoes.log}: uma linha por DECISÃO (ocorrência, kick, ban,
 * descarte na revalidação, revisão). Nunca por flag. Gravado pela {@link IoWorker}.
 */
final class AuditLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File file;
    private final IoWorker io;
    private final Logger logger;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder failed = new LongAdder();

    AuditLog(File dataFolder, IoWorker io, Logger logger) {
        this.file = new File(dataFolder, "decisoes.log");
        this.io = io;
        this.logger = logger;
    }

    void write(String line) {
        String stamped = "[" + LocalDateTime.now().format(TS) + "] " + line + System.lineSeparator();
        if (!io.submit(() -> append(stamped))) {
            dropped.increment();
        }
    }

    private void append(String stamped) {
        try {
            Files.writeString(file.toPath(), stamped, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            failed.increment();
            logger.warning("Falha ao gravar decisoes.log: " + e.getMessage());
        }
    }

    long dropped() {
        return dropped.sum();
    }

    long failed() {
        return failed.sum();
    }
}
