package dev.triedge.common.queue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.testing.TestDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JobQueueIT {

    private static final String QUEUE = "create-repo";

    private static JdbcClient jdbc;
    private static JobQueue queue;

    @BeforeAll
    static void connect() {
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        queue = new JobQueue(jdbc, "platform", Duration.ofSeconds(60));
    }

    @BeforeEach
    void emptyQueue() {
        jdbc.sql("TRUNCATE platform.job_queue").update();
    }

    @Test
    void duplicateDedupKeyIsIgnored() {
        assertThat(queue.enqueue(QUEUE, "repo-1", "{}")).isTrue();
        assertThat(queue.enqueue(QUEUE, "repo-1", "{}")).isFalse();
        assertThat(queue.enqueue(QUEUE, null, "{}")).isTrue();
        assertThat(queue.enqueue(QUEUE, null, "{}")).isTrue();
        assertThat(count("1 = 1")).isEqualTo(3);
    }

    @Test
    void claimedJobIsNotHandedOutTwice() {
        queue.enqueue(QUEUE, null, "{\"repoId\":1}");

        Optional<Job> first = queue.claim(QUEUE);
        assertThat(first).hasValueSatisfying(job -> {
            assertThat(job.payload()).isEqualTo("{\"repoId\": 1}");
            assertThat(job.attempts()).isEqualTo(1);
        });
        assertThat(queue.claim(QUEUE)).isEmpty();
        assertThat(queue.claim("other-queue")).isEmpty();
    }

    @Test
    void concurrentWorkersProcessEachJobExactlyOnce() throws Exception {
        int jobs = 300;
        for (int i = 0; i < jobs; i++) {
            queue.enqueue(QUEUE, "job-" + i, "{}");
        }
        Map<Long, AtomicInteger> processed = new ConcurrentHashMap<>();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                workers.add(executor.submit(() -> {
                    Optional<Job> job;
                    while ((job = queue.claim(QUEUE)).isPresent()) {
                        processed.computeIfAbsent(job.get().id(), id -> new AtomicInteger()).incrementAndGet();
                        assertThat(queue.complete(job.get())).isTrue();
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(1, TimeUnit.MINUTES);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(processed).hasSize(jobs);
        assertThat(processed.values()).allSatisfy(times -> assertThat(times.get()).isEqualTo(1));
        assertThat(count("status = 'DONE'")).isEqualTo(jobs);
    }

    @Test
    void expiredLockIsTakenOverAndStaleWorkerIsFenced() throws Exception {
        JobQueue shortLocks = new JobQueue(jdbc, "platform", Duration.ofMillis(200));
        shortLocks.enqueue(QUEUE, null, "{}");

        Job stale = shortLocks.claim(QUEUE).orElseThrow();
        Thread.sleep(400);
        Job current = shortLocks.claim(QUEUE).orElseThrow();

        assertThat(current.id()).isEqualTo(stale.id());
        assertThat(current.attempts()).isEqualTo(2);
        assertThat(shortLocks.complete(stale)).isFalse();
        assertThat(shortLocks.extendLock(stale)).isFalse();
        assertThat(shortLocks.extendLock(current)).isTrue();
        assertThat(shortLocks.complete(current)).isTrue();
    }

    @Test
    void failedJobIsRetriedLaterAndEventuallyGivesUp() {
        queue.enqueue(QUEUE, null, "{}");

        for (int attempt = 1; attempt <= 5; attempt++) {
            Job job = queue.claim(QUEUE).orElseThrow();
            assertThat(job.attempts()).isEqualTo(attempt);
            assertThat(queue.fail(job, "boom " + attempt)).isTrue();
            assertThat(queue.claim(QUEUE)).as("retry must wait for its backoff").isEmpty();
            jdbc.sql("UPDATE platform.job_queue SET run_after = now()").update();
        }

        assertThat(queue.claim(QUEUE)).isEmpty();
        assertThat(count("status = 'FAILED' AND last_error = 'boom 5'")).isEqualTo(1);
    }

    @Test
    void expiredLockOnFinalAttemptMarksJobFailed() throws Exception {
        JobQueue shortLocks = new JobQueue(jdbc, "platform", Duration.ofMillis(200));
        shortLocks.enqueue(QUEUE, null, "{}");
        jdbc.sql("UPDATE platform.job_queue SET max_attempts = 1").update();

        shortLocks.claim(QUEUE).orElseThrow();
        Thread.sleep(400);

        assertThat(shortLocks.claim(QUEUE)).isEmpty();
        assertThat(count("status = 'FAILED'")).isEqualTo(1);
    }

    @Test
    void longErrorsAreTrimmed() {
        queue.enqueue(QUEUE, null, "{}");
        Job job = queue.claim(QUEUE).orElseThrow();

        queue.fail(job, "x".repeat(5000));

        assertThat(jdbc.sql("SELECT length(last_error) FROM platform.job_queue").query(Integer.class).single())
                .isEqualTo(2000);
    }

    private static int count(String condition) {
        return jdbc.sql("SELECT count(*) FROM platform.job_queue WHERE " + condition).query(Integer.class).single();
    }
}
