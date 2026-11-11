package com.str.backend.kp.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Ograničeni pool za slanje u KP API izvan niti zahtjeva, kao {@code EgopKpExecutor}: odgovor službeniku
 * ne čeka KP. Poruka je zapisana prije slanja, pa je odbijanje zadatka bezopasno — šalje je sljedeći
 * zahtjev službenika iste županije. Namjerno nije {@code Executor} bean ni {@code @Async}.
 */
public class KpApiExecutor {

    private static final Logger log = LoggerFactory.getLogger(KpApiExecutor.class);

    private final Executor executor;

    KpApiExecutor(KpApiProperties properties) {
        ThreadPoolTaskExecutor p = new ThreadPoolTaskExecutor();
        p.setThreadNamePrefix("kp-api-");
        p.setCorePoolSize(properties.poolSize());
        p.setMaxPoolSize(properties.poolSize());
        p.setQueueCapacity(properties.queueCapacity());
        p.setRejectedExecutionHandler((task, pool) ->
                log.warn("kp_api_async_rejected queue={} — poruku šalje sljedeći zahtjev službenika",
                        pool.getQueue().size()));
        p.setWaitForTasksToCompleteOnShutdown(true);
        p.setAwaitTerminationSeconds(20);
        p.initialize();
        this.executor = p;
    }

    private KpApiExecutor(Executor executor) {
        this.executor = executor;
    }

    /** Izvodi zadatak na pozivajućoj niti — za testove. */
    static KpApiExecutor direct() {
        return new KpApiExecutor(Runnable::run);
    }

    void submit(Runnable task) {
        executor.execute(task);
    }
}
