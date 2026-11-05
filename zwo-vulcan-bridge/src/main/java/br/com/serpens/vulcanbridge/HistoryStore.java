package br.com.serpens.vulcanbridge;

import br.com.serpens.vulcanbridge.core.OffenderRecord;
import br.com.serpens.vulcanbridge.core.OffenderRecord.BanState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Histórico de ocorrências por UUID em {@code plugins/ZwoVulcanBridge/historico.yml}.
 *
 * <p>Fica em memória (só jogadores com kick confirmado entram aqui) e é gravado na thread de I/O:
 * cada alteração marca "sujo" e no máximo uma gravação fica na fila. O arquivo é trocado de forma
 * atômica (arquivo temporário + move), então um desligamento no meio não corrompe o histórico.</p>
 */
final class HistoryStore {

    private static final int FORMAT = 1;

    private final File file;
    private final IoWorker io;
    private final Logger logger;
    private final Map<UUID, OffenderRecord> records = new ConcurrentHashMap<>();
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private volatile boolean dirty;
    private final LongAdder saveFailures = new LongAdder();
    private volatile String lastError = "";
    private volatile long lastSavedAt;

    HistoryStore(File dataFolder, IoWorker io, Logger logger) {
        this.file = new File(dataFolder, "historico.yml");
        this.io = io;
        this.logger = logger;
    }

    /**
     * Lê o arquivo (na inicialização). Pedidos de ban que ficaram "em andamento" viram REVIEW: não
     * dá para saber se o ModPanel aplicou, e a ponte nunca repete o pedido sozinha.
     */
    int load(long now, long validityMillis) {
        records.clear();
        if (!file.isFile()) {
            return 0;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = y.getConfigurationSection("jogadores");
        if (players == null) {
            return 0;
        }
        int pendingToReview = 0;
        for (String key : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(key);
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (s == null) {
                continue;
            }
            BanState ban;
            try {
                ban = BanState.valueOf(s.getString("ban", "NONE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                ban = BanState.REVIEW;
            }
            String detail = s.getString("ban-detalhe", "");
            if (ban == BanState.PENDING) {
                ban = BanState.REVIEW;
                detail = "servidor reiniciou com o pedido de ban em andamento: resultado incerto, conferir no ModPanel";
                pendingToReview++;
            }
            OffenderRecord r = new OffenderRecord(id, s.getString("nome", ""), s.getInt("kicks", 0),
                    s.getLong("primeiro-kick", 0L), s.getLong("ultimo-kick", 0L), s.getLong("ultima-sessao", 0L),
                    ban, s.getLong("ban-em", 0L), s.getString("pedido", ""), detail);
            if (!r.expired(now, validityMillis)) {
                records.put(id, r);
            }
        }
        if (pendingToReview > 0) {
            logger.warning(pendingToReview + " pedido(s) de ban estavam em andamento no desligamento: marcados "
                    + "para revisão (confira no ModPanel). Nenhum ban será repetido automaticamente.");
            requestSave();
        }
        return records.size();
    }

    Optional<OffenderRecord> get(UUID id) {
        return Optional.ofNullable(records.get(id));
    }

    Collection<OffenderRecord> all() {
        return records.values();
    }

    int size() {
        return records.size();
    }

    /** Thread principal. */
    void put(OffenderRecord r) {
        records.put(r.playerId(), r);
        requestSave();
    }

    /** Thread principal. */
    boolean remove(UUID id) {
        boolean removed = records.remove(id) != null;
        if (removed) {
            requestSave();
        }
        return removed;
    }

    /** Remove entradas vencidas. Thread principal (timer). */
    int purgeExpired(long now, long validityMillis) {
        List<UUID> expired = new ArrayList<>();
        for (OffenderRecord r : records.values()) {
            if (r.expired(now, validityMillis) && r.ban() != BanState.PENDING) {
                expired.add(r.playerId());
            }
        }
        expired.forEach(records::remove);
        if (!expired.isEmpty()) {
            requestSave();
        }
        return expired.size();
    }

    /** Agenda uma gravação (no máximo uma na fila). */
    void requestSave() {
        dirty = true;
        if (saveQueued.compareAndSet(false, true) && !io.submit(this::writeNow)) {
            saveQueued.set(false);
            saveFailures.increment();
            lastError = "fila de I/O cheia; nova tentativa no próximo ciclo";
        }
    }

    /** Timer: tenta de novo se uma gravação anterior falhou. */
    void retryIfDirty() {
        if (dirty) {
            requestSave();
        }
    }

    /** No desligamento, depois de esvaziar a fila de I/O. */
    void flushSync() {
        if (dirty) {
            writeNow();
        }
    }

    private void writeNow() {
        saveQueued.set(false);
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Histórico de ocorrências da ZwoVulcanBridge, por UUID. Gerado pelo plugin.",
                "Para zerar um jogador use /zvb limpar <nick|uuid> (não edite com o servidor ligado)."));
        y.set("versao", FORMAT);
        for (OffenderRecord r : List.copyOf(records.values())) {
            String p = "jogadores." + r.playerId() + ".";
            y.set(p + "nome", r.name());
            y.set(p + "kicks", r.kicks());
            y.set(p + "primeiro-kick", r.firstKickAt());
            y.set(p + "ultimo-kick", r.lastKickAt());
            y.set(p + "ultima-sessao", r.lastSessionId());
            y.set(p + "ban", r.ban().name());
            y.set(p + "ban-em", r.banAt());
            y.set(p + "pedido", r.banRequestId());
            y.set(p + "ban-detalhe", r.banDetail());
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Path tmp = file.toPath().resolveSibling("historico.yml.tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            lastSavedAt = System.currentTimeMillis();
            lastError = "";
        } catch (IOException e) {
            dirty = true;
            saveFailures.increment();
            lastError = e.toString();
            logger.warning("Falha ao gravar historico.yml: " + e.getMessage());
        }
    }

    boolean dirty() { return dirty; }
    long saveFailures() { return saveFailures.sum(); }
    String lastError() { return lastError; }
    long lastSavedAt() { return lastSavedAt; }
}
