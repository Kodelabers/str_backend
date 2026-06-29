package com.str.backend.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes admin-action audit rows. The actor is a placeholder (caller-supplied id or
 * {@link #SYSTEM}) until NIAS roles provide a reliable identity (BX0).
 */
@Service
public class AdminAuditService {

    /** Actor for automated/unauthenticated actions until NIAS roles land. */
    public static final String SYSTEM = "SYSTEM";

    private final AdminAuditLogRepository repository;

    public AdminAuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String actor, String action, String entityType, String entityId, String details) {
        repository.save(AdminAuditLogEntity.record(
                actor != null ? actor : SYSTEM, action, entityType, entityId, details));
    }
}
