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
 * wave finishes before the next starts so {@link ValidationContext} flags set by
 * upstream checks (e.g. GO-2 markCoOwnerConsentRequired for GO-4) are visible without races.
 *
 * Within a wave the first Rejected short-circuits the remaining checks of that wave
 * (others may already be running, their results are ignored).
 */
@Service
public class ParallelValidationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ParallelValidationOrchestrator.class);
    private static final String ENTITY_TYPE = "SSO";

    private final List<ValidationCheck> checks;
    private final AuditLogRepository auditLogRepository;
    private final ExecutorService executor;

    public ParallelValidationOrchestrator(List<ValidationCheck> checks,
                                          AuditLogRepository auditLogRepository) {
        this.checks = checks;
        this.auditLogRepository = auditLogRepository;
        this.executor = Executors.newFixedThreadPool(Math.max(2, checks.size()));
    }

    public PipelineResult execute(ValidationContext context) {
        String accommodationId = context.accommodation().getAccommodationId().toString();
        List<List<ValidationCheck>> waves = planWaves();

        for (List<ValidationCheck> wave : waves) {
            PipelineResult result = runWave(wave, context, accommodationId);
            if (result != null) {
                return result;
            }
        }
        auditLogRepository.save(AuditLogEntity.validation(
                ENTITY_TYPE, accommodationId, "PIPELINE", "PASSED", null));
        return PipelineResult.passed();
    }

    private PipelineResult runWave(List<ValidationCheck> wave,
                                   ValidationContext context, String accommodationId) {
        List<CompletableFuture<ValidationResult>> futures = new ArrayList<>(wave.size());
        for (ValidationCheck c : wave) {
            futures.add(CompletableFuture.supplyAsync(() -> c.check(context), executor));
        }
        for (int i = 0; i < wave.size(); i++) {
            ValidationCheck c = wave.get(i);
            ValidationResult r;
            try {
                r = futures.get(i).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ExternalRegistryException(c.step(), "validation interrupted", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof ExternalRegistryException ex) throw ex;
                if (cause instanceof RuntimeException re) throw re;
                throw new ExternalRegistryException(c.step(), "validation failed", cause);
            }
            PipelineResult resolved = record(accommodationId, r);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private List<List<ValidationCheck>> planWaves() {
        Map<String, ValidationCheck> byStep = new HashMap<>();
        for (ValidationCheck c : checks) {
            byStep.put(c.step(), c);
        }
        List<ValidationCheck> remaining = new ArrayList<>(checks);
        Set<String> completed = new HashSet<>();
        List<List<ValidationCheck>> waves = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<ValidationCheck> wave = new ArrayList<>();
            for (ValidationCheck c : remaining) {
                if (completed.containsAll(c.dependsOn())) {
                    wave.add(c);
                }
            }
            if (wave.isEmpty()) {
                throw new IllegalStateException(
                        "validation dependsOn cycle or unknown step among: " + remaining);
            }
            wave.sort(Comparator.comparingInt(ValidationCheck::order));
            waves.add(wave);
            remaining.removeAll(wave);
            for (ValidationCheck c : wave) completed.add(c.step());
        }
        return waves;
    }

    private PipelineResult record(String accommodationId, ValidationResult r) {
        return switch (r) {
            case ValidationResult.Passed p -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        ENTITY_TYPE, accommodationId, p.getStep(), "PASSED", p.getDetail()));
                log.info("go_pass accommodation={} step={} detail={}", accommodationId, p.getStep(), p.getDetail());
                yield null;
            }
            case ValidationResult.Rejected o -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        ENTITY_TYPE, accommodationId, o.getStep(), "REJECTED", o.getReason()));
                log.warn("go_reject accommodation={} step={} reason={}", accommodationId, o.getStep(), o.getReason());
                yield PipelineResult.rejected(o.getStep(), o.getReason());
            }
        };
    }
}
