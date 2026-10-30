package dev.triedge.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.testing.TestDatabase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxOrderingIT {

    private static final int WRITERS = 8;
    private static final int TRANSACTIONS_PER_WRITER = 250;

    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static OutboxWriter writer;

    @BeforeAll
    static void connect() {
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(TestDatabase.dataSource()));
        writer = new OutboxWriter(jdbc, "platform");
    }

    @BeforeEach
    void emptyOutbox() {
        jdbc.sql("TRUNCATE platform.outbox").update();
    }

    @Test
    void openTransactionHoldsBackLaterEvents() throws Exception {
        OutboxTailer tailer = new OutboxTailer(jdbc, "platform", 100);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> slow = executor.submit(() -> transactions.executeWithoutResult(status -> {
                writer.append("repo", "slow", "REF_UPDATED", "{}");
                written.countDown();
                await(release);
            }));
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();

            transactions.executeWithoutResult(status -> writer.append("repo", "fast", "REF_UPDATED", "{}"));

            assertThat(tailer.poll(Checkpoint.START)).isEmpty();

            release.countDown();
            slow.get(5, TimeUnit.SECONDS);

            assertThat(tailer.poll(Checkpoint.START))
                    .extracting(OutboxEvent::aggregateId)
                    .containsExactly("slow", "fast");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rolledBackEventsNeverAppear() {
        transactions.executeWithoutResult(status -> {
            writer.append("repo", "gone", "REF_UPDATED", "{}");
            status.setRollbackOnly();
        });
        transactions.executeWithoutResult(status -> writer.append("repo", "kept", "REF_UPDATED", "{}"));

        assertThat(new OutboxTailer(jdbc, "platform", 100).poll(Checkpoint.START))
                .extracting(OutboxEvent::aggregateId)
                .containsExactly("kept");
    }

    @Test
    void concurrentWritersLoseNothing() throws Exception {
        Set<String> committed = ConcurrentHashMap.newKeySet();
        List<OutboxEvent> read = new ArrayList<>();
        AtomicBoolean writing = new AtomicBoolean(true);
        OutboxTailer tailer = new OutboxTailer(jdbc, "platform", 50);

        ExecutorService executor = Executors.newFixedThreadPool(WRITERS + 1);
        try {
            Future<?> reader = executor.submit(() -> {
                Checkpoint position = Checkpoint.START;
                int emptyPollsAfterWriters = 0;
                while (emptyPollsAfterWriters < 3) {
                    List<OutboxEvent> batch = tailer.poll(position);
                    if (batch.isEmpty()) {
                        if (!writing.get()) {
                            emptyPollsAfterWriters++;
                        }
                        sleep(5);
                    } else {
                        read.addAll(batch);
                        position = Checkpoint.of(batch.getLast());
                    }
                }
            });

            List<Future<?>> writers = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                int writerId = w;
                writers.add(executor.submit(() -> {
                    for (int t = 0; t < TRANSACTIONS_PER_WRITER; t++) {
                        String key = writerId + "-" + t;
                        boolean rollback = ThreadLocalRandom.current().nextInt(10) == 0;
                        transactions.executeWithoutResult(status -> {
                            writer.append("repo", key, "REF_UPDATED", "{}");
                            jdbc.sql("SELECT pg_sleep(:seconds)")
                                    .param("seconds", ThreadLocalRandom.current().nextInt(20) / 1000.0)
                                    .query().singleRow();
                            if (rollback) {
                                status.setRollbackOnly();
                            }
                        });
                        if (!rollback) {
                            committed.add(key);
                        }
                    }
                }));
            }
            for (Future<?> w : writers) {
                w.get(2, TimeUnit.MINUTES);
            }
            writing.set(false);
            reader.get(1, TimeUnit.MINUTES);
        } finally {
            executor.shutdownNow();
        }

        Set<String> readKeys = new HashSet<>();
        for (OutboxEvent event : read) {
            assertThat(readKeys.add(event.aggregateId())).as("duplicate %s", event.aggregateId()).isTrue();
        }
        assertThat(readKeys).isEqualTo(committed);
        assertThat(read).isSortedAccordingTo((a, b) -> a.txid() != b.txid()
                ? Long.compare(a.txid(), b.txid())
                : Long.compare(a.id(), b.id()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
