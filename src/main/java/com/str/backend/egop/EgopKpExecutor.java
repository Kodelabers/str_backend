package com.str.backend.egop;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Zasebni, ograničeni pool u kojem se otprema u korisnički pretinac izvodi izvan niti zahtjeva.
 *
 * <p>Otprema je outbox: redak u {@code egop_kp_otprema} zapisan je prije slanja, pa pool služi samo
 * da dostava krene odmah, a ne tek s {@link EgopRetryJob}-om. Zato je odbijanje bezopasno — zadatak
 * koji ne stane u red odbacuje se uz WARN, a retry job ga pošalje nakon grace roka. Namjerno nije
 * {@code CallerRunsPolicy}: on bi slanje vratio na nit zahtjeva. Isto vrijedi za gašenje: čeka se
 * kratko da tekući pozivi završe, ostalo pokupi retry.
 *
 * <p>Red je namjerno kratak. Zadatak koji čeka dulje od grace roka ({@code str.egop.retry.grace},
 * 5 min) mogao bi se poslati istovremeno s retry jobom; uz 2 niti, 20 mjesta i read timeout od
 * 15 s najgori slučaj je ~150 s.
 *
 * <p>Namjerno <b>nije</b> {@code Executor} bean ni {@code @Async}: Spring Boot gasi svoj
 * {@code applicationTaskExecutor} čim u kontekstu postoji ijedan {@code Executor}, a
 * {@code @EnableAsync} bi uključio asinkronost za cijelu aplikaciju.
 */
@Component
public class EgopKpExecutor {

    private static final Logger log = LoggerFactory.getLogger(EgopKpExecutor.class);

    private final Executor executor;
    private final ThreadPoolTaskExecutor pool;

    @Autowired
    public EgopKpExecutor(@Value("${str.egop.kp.async.pool-size:2}") int poolSize,
                          @Value("${str.egop.kp.async.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor p = new ThreadPoolTaskExecutor();
        p.setThreadNamePrefix("egop-kp-");
        // Fiksna veličina: eGOP je jedan vanjski servis, više paralelnih poziva ga ne ubrzava.
        p.setCorePoolSize(poolSize);
        p.setMaxPoolSize(poolSize);
        p.setQueueCapacity(queueCapacity);
        p.setRejectedExecutionHandler((task, threadPool) ->
                log.warn("egop_kp_async_rejected queue={} — otpremu šalje retry job nakon grace roka",
                        threadPool.getQueue().size()));
        p.setWaitForTasksToCompleteOnShutdown(true);
        p.setAwaitTerminationSeconds(20);
        p.initialize();
        this.pool = p;
        this.executor = p;
    }

    private EgopKpExecutor(Executor executor) {
        this.executor = executor;
        this.pool = null;
    }

    /** Izvodi zadatak na pozivajućoj niti — za testove. */
    static EgopKpExecutor direct() {
        return new EgopKpExecutor(Runnable::run);
    }

    void submit(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.destroy();
        }
    }
}
