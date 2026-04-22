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

    private final List<ValidacijskaProvjera> provjere;
    private final AuditLogRepository auditLogRepository;

    public ValidacijskiOrkestrator(List<ValidacijskaProvjera> provjere, AuditLogRepository auditLogRepository) {
        this.provjere = provjere.stream()
                .sorted(Comparator.comparingInt(ValidacijskaProvjera::order))
                .toList();
        this.auditLogRepository = auditLogRepository;
    }

    public PipelineRezultat izvrsi(ValidacijskiKontekst kontekst) {
        for (ValidacijskaProvjera provjera : provjere) {
            ValidacijskiRezultat r = provjera.provjeri(kontekst);
            PipelineRezultat pipelineRezultat = zabiljezi(kontekst, r);
            if (pipelineRezultat != null) {
                return pipelineRezultat;
            }
        }
        auditLogRepository.save(AuditLogEntity.validation(
                kontekst.sso().getUuidSso(), "PIPELINE", "PROSAO", null));
        return PipelineRezultat.prosao();
    }

    private PipelineRezultat zabiljezi(ValidacijskiKontekst kontekst, ValidacijskiRezultat r) {
        return switch (r) {
            case ValidacijskiRezultat.Prosla p -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        kontekst.sso().getUuidSso(), p.step(), "PROSLA", p.detail()));
                log.info("go_pass uuidSso={} step={} detail={}",
                        kontekst.sso().getUuidSso(), p.step(), p.detail());
                yield null;
            }
            case ValidacijskiRezultat.Odbijena o -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        kontekst.sso().getUuidSso(), o.step(), "ODBIJENA", o.razlog()));
                log.warn("go_reject uuidSso={} step={} razlog={}",
                        kontekst.sso().getUuidSso(), o.step(), o.razlog());
                yield PipelineRezultat.odbijen(o.step(), o.razlog());
            }
            case ValidacijskiRezultat.CekaCallback c -> {
                auditLogRepository.save(AuditLogEntity.validation(
                        kontekst.sso().getUuidSso(), c.step(), "CEKA_CALLBACK", c.razlog()));
                log.info("go_pending uuidSso={} step={} razlog={}",
                        kontekst.sso().getUuidSso(), c.step(), c.razlog());
                yield PipelineRezultat.cekaCallback(c.step(), c.razlog());
            }
        };
    }
}
