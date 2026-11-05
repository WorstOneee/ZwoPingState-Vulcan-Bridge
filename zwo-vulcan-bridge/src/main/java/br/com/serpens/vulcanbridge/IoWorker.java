package br.com.serpens.vulcanbridge;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Uma thread de I/O com fila LIMITADA, para a auditoria e o histórico. Fila cheia = tarefa
 * recusada e contada; quem chama decide o que fazer (nunca bloqueia a thread do servidor).
 */
final class IoWorker {

    static final int QUEUE_SIZE = 1024;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_SIZE), r -> {
        Thread t = new Thread(r, "ZwoVulcanBridge-IO");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.AbortPolicy());

    private final LongAdder rejected = new LongAdder();

    boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            rejected.increment();
            return false;
        }
    }

    long rejected() {
        return rejected.sum();
    }

    int queued() {
        return executor.getQueue().size();
    }

    /** Termina o que está na fila (até 5 s). */
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
