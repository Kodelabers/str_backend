package com.str.backend.validation;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class ValidacijskiOrkestrator {

    private static final Logger log = LoggerFactory.getLogger(ValidacijskiOrkestrator.class);
    private static final String ENTITY_TYPE = "SSO";

    private final List<ValidacijskaProvjera> provjere;
    private final AuditLogRepository auditLogRepository;

    public ValidacijskiOrkestrator(List<ValidacijskaProvjera> provjere, AuditLogRepository auditLogRepository) {
        this.provjere = provjere.stream()
                .sorted(Comparator.comparingInt(ValidacijskaProvjera::order))
                .toList();
        this.auditLogRepository = auditLogRepository;
    }

    public PipelineRezultat izvrsi(ValidacijskiKontekst kontekst) {
        String ssoId = kontekst.sso().getIdSso().toString();
        for (ValidacijskaProvjera provjera : provjere) {
            ValidacijskiRezultat r = provjera.provjeri(kontekst);
            PipelineRezultat pipelineRezultat = zabiljezi(ssoId, r);
            if (pipelineRezultat != null) {
                return pipelineRezultat;
            }
        }
        auditLogRepository.save(AuditLogEntity.validation(
                ENTITY_TYPE, ssoId, "PIPELINE", "PROSAO", null));
        return PipelineRezultat.prosao();
    }

    private PipelineRezultat zabiljezi(String ssoId, ValidacijskiRezultat r) {
        return switch (r) {
            case ValidacijskiRezultat.Prosla p -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        ENTITY_TYPE, ssoId, p.step(), "PROSLA", p.detail()));
                log.info("go_pass sso={} step={} detail={}", ssoId, p.step(), p.detail());
                yield null;
            }
            case ValidacijskiRezultat.Odbijena o -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        ENTITY_TYPE, ssoId, o.step(), "ODBIJENA", o.razlog()));
                log.warn("go_reject sso={} step={} razlog={}", ssoId, o.step(), o.razlog());
                yield PipelineRezultat.odbijen(o.step(), o.razlog());
            }
        };
    }
}
