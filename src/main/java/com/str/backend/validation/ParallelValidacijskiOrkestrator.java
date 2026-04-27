package com.str.backend.validation;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.exception.ExternalRegistryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STR §5/§6: GO checks run in parallel waves. Independent checks fan out at once;
 * checks with dependsOn() start only after their predecessors complete. The whole
 * wave finishes before the next starts so {@link ValidacijskiKontekst} flags set by
 * upstream checks (e.g. GO-2 markiraj for GO-4) are visible without races.
 *
 * Within a wave the first Odbijena short-circuits the remaining checks of that wave
 * (others may already be running, their results are ignored).
 */
@Service
public class ParallelValidacijskiOrkestrator {

    private static final Logger log = LoggerFactory.getLogger(ParallelValidacijskiOrkestrator.class);
    private static final String ENTITY_TYPE = "SSO";

    private final List<ValidacijskaProvjera> provjere;
    private final AuditLogRepository auditLogRepository;
    private final ExecutorService executor;

    public ParallelValidacijskiOrkestrator(List<ValidacijskaProvjera> provjere,
                                           AuditLogRepository auditLogRepository) {
        this.provjere = provjere;
        this.auditLogRepository = auditLogRepository;
        this.executor = Executors.newFixedThreadPool(Math.max(2, provjere.size()));
    }

    public PipelineRezultat izvrsi(ValidacijskiKontekst kontekst) {
        String ssoId = kontekst.sso().getIdSso().toString();
        List<List<ValidacijskaProvjera>> waves = planWaves();

        for (List<ValidacijskaProvjera> wave : waves) {
            PipelineRezultat rez = runWave(wave, kontekst, ssoId);
            if (rez != null) {
                return rez;
            }
        }
        auditLogRepository.save(AuditLogEntity.validation(
                ENTITY_TYPE, ssoId, "PIPELINE", "PROSAO", null));
        return PipelineRezultat.prosao();
    }

    private PipelineRezultat runWave(List<ValidacijskaProvjera> wave,
                                     ValidacijskiKontekst kontekst, String ssoId) {
        List<CompletableFuture<ValidacijskiRezultat>> futures = new ArrayList<>(wave.size());
        for (ValidacijskaProvjera p : wave) {
            futures.add(CompletableFuture.supplyAsync(() -> p.provjeri(kontekst), executor));
        }
        for (int i = 0; i < wave.size(); i++) {
            ValidacijskaProvjera p = wave.get(i);
            ValidacijskiRezultat r;
            try {
                r = futures.get(i).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ExternalRegistryException(p.step(), "validation interrupted", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof ExternalRegistryException ex) throw ex;
                if (cause instanceof RuntimeException re) throw re;
                throw new ExternalRegistryException(p.step(), "validation failed", cause);
            }
            PipelineRezultat resolved = zabiljezi(ssoId, r);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private List<List<ValidacijskaProvjera>> planWaves() {
        Map<String, ValidacijskaProvjera> byStep = new HashMap<>();
        for (ValidacijskaProvjera p : provjere) {
            byStep.put(p.step(), p);
        }
        List<ValidacijskaProvjera> remaining = new ArrayList<>(provjere);
        Set<String> completed = new HashSet<>();
        List<List<ValidacijskaProvjera>> waves = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<ValidacijskaProvjera> wave = new ArrayList<>();
            for (ValidacijskaProvjera p : remaining) {
                if (completed.containsAll(p.dependsOn())) {
                    wave.add(p);
                }
            }
            if (wave.isEmpty()) {
                throw new IllegalStateException(
                        "validation dependsOn cycle or unknown step among: " + remaining);
            }
            wave.sort(Comparator.comparingInt(ValidacijskaProvjera::order));
            waves.add(wave);
            remaining.removeAll(wave);
            for (ValidacijskaProvjera p : wave) completed.add(p.step());
        }
        return waves;
    }

    private PipelineRezultat zabiljezi(String ssoId, ValidacijskiRezultat r) {
        return switch (r) {
            case ValidacijskiRezultat.Prosla p -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        ENTITY_TYPE, ssoId, p.getStep(), "PROSLA", p.getDetail()));
                log.info("go_pass sso={} step={} detail={}", ssoId, p.getStep(), p.getDetail());
                yield null;
            }
            case ValidacijskiRezultat.Odbijena o -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        ENTITY_TYPE, ssoId, o.getStep(), "ODBIJENA", o.getRazlog()));
                log.warn("go_reject sso={} step={} razlog={}", ssoId, o.getStep(), o.getRazlog());
                yield PipelineRezultat.odbijen(o.getStep(), o.getRazlog());
            }
        };
    }
}
