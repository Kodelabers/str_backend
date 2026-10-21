package com.str.backend.kp;

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
 * <p>Otprema je outbox: redak u {@code kp_otprema} zapisan je prije slanja, pa pool služi samo
 * da dostava krene odmah, a ne tek s retry jobom ({@code EgopRetryJob}). Zato je odbijanje
 * bezopasno — zadatak koji ne stane u red odbacuje se uz WARN, a retry job ga pošalje nakon grace
 * roka. Namjerno nije
 * {@code CallerRunsPolicy}: on bi slanje vratio na nit zahtjeva. Isto vrijedi za gašenje: čeka se
 * kratko da tekući pozivi završe, ostalo pokupi retry.
 *
 * <p>Red je namjerno kratak. Zadatak koji čeka dulje od grace roka ({@code str.egop.retry.grace},
 * 5 min) mogao bi se poslati istovremeno s retry jobom; uz 2 niti, 20 mjesta i read timeout od
 * 20 s najgori slučaj je ~200 s. Dvostruko slanje i tada nije dvostruka dostava: oba poziva nose
 * isti {@code IdPoruke}, pa drugi završi kao duplikat (K009/Z012).
 *
 * <p>Namjerno <b>nije</b> {@code Executor} bean ni {@code @Async}: Spring Boot gasi svoj
 * {@code applicationTaskExecutor} čim u kontekstu postoji ijedan {@code Executor}, a
 * {@code @EnableAsync} bi uključio asinkronost za cijelu aplikaciju.
 */
@Component
public class KpExecutor {

    private static final Logger log = LoggerFactory.getLogger(KpExecutor.class);

    private final Executor executor;
    private final ThreadPoolTaskExecutor pool;

    @Autowired
    public KpExecutor(@Value("${str.kp.async.pool-size:2}") int poolSize,
                      @Value("${str.kp.async.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor p = new ThreadPoolTaskExecutor();
        p.setThreadNamePrefix("kp-");
        // Fiksna veličina: SZP je jedan vanjski servis, više paralelnih poziva ga ne ubrzava.
        p.setCorePoolSize(poolSize);
        p.setMaxPoolSize(poolSize);
        p.setQueueCapacity(queueCapacity);
        p.setRejectedExecutionHandler((task, threadPool) ->
                log.warn("kp_async_rejected queue={} — otpremu šalje retry job nakon grace roka",
                        threadPool.getQueue().size()));
        p.setWaitForTasksToCompleteOnShutdown(true);
        p.setAwaitTerminationSeconds(20);
        p.initialize();
        this.pool = p;
        this.executor = p;
    }

    private KpExecutor(Executor executor) {
        this.executor = executor;
        this.pool = null;
    }

    /** Izvodi zadatak na pozivajućoj niti — za testove. */
    static KpExecutor direct() {
        return new KpExecutor(Runnable::run);
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
