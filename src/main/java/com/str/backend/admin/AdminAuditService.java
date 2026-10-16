package com.str.backend.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes admin-action audit rows. The actor is the official from the NIAS session
 * ({@code INTERNAL:<oib>}, see {@code InternalAccessService#actorFor}), or {@link #SYSTEM}
 * for automated actions.
 */
@Service
public class AdminAuditService {

    /** Actor for automated actions (jobs) — no official behind them. */
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
