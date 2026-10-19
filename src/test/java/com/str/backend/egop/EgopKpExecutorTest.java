package com.str.backend.egop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EgopKpExecutorTest {

    private EgopKpExecutor executor;

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /** Otprema ne smije čekati na niti pozivatelja — to je nit HTTP zahtjeva. */
    @Test
    void submit_runsOnPoolThread() throws Exception {
        executor = new EgopKpExecutor(1, 5);
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.submit(() -> {
            thread.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).startsWith("egop-kp-").isNotEqualTo(Thread.currentThread().getName());
    }

    /**
     * Pun red ne smije baciti iznimku pozivatelju (urudžbi) ni vratiti posao na njegovu nit —
     * odbačenu otpremu šalje retry job.
     */
    @Test
    void submit_queueFull_dropsWithoutThrowingOrRunningInline() throws Exception {
        executor = new EgopKpExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        executor.submit(blocker);   // zauzme jedinu nit
        executor.submit(blocker);   // jedino mjesto u redu
        AtomicReference<String> rejectedRanOn = new AtomicReference<>();

        assertThatCode(() -> executor.submit(() -> rejectedRanOn.set(Thread.currentThread().getName())))
                .doesNotThrowAnyException();

        release.countDown();
        assertThat(rejectedRanOn.get()).isNull();
    }
}
