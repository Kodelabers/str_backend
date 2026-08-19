package com.str.backend.auth.role;

import com.str.backend.str.StrApplicationUserEntity;
import com.str.backend.str.StrApplicationUserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Razrješuje STR ulogu iz OIB-a: gleda {@code str.application_user} (po {@code username = oib})
 * i njegovu {@code internal} zastavicu.
 *
 * <ul>
 *   <li>interni korisnik ({@code internal = true}) → {@link StrRoles#ROLE_INTERNAL}</li>
 *   <li>sve ostalo (nije nađen / {@code internal} null ili false) → {@link StrRoles#ROLE_USER}</li>
 * </ul>
 *
 * <p>Namjerno se NE oslanja na {@code str.application_user_roles} — konkretne role (INTERNAL_USER,
 * INSPEKTORAT, …) još ne postoje u shemi. Kad se dodaju, ovdje je jedino mjesto koje treba proširiti.
 */
@Service
public class InternalUserResolver {

    private final StrApplicationUserRepository repository;

    public InternalUserResolver(StrApplicationUserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean isInternal(String oib) {
        if (oib == null || oib.isBlank()) {
            return false;
        }
        return repository.findFirstByUsernameAndActiveTrue(oib.trim())
                .map(StrApplicationUserEntity::getInternal)
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }

    /** Puni authority naziv uloge ({@code ROLE_INTERNAL} / {@code ROLE_USER}). */
    public String resolveRole(String oib) {
        return isInternal(oib) ? StrRoles.ROLE_INTERNAL : StrRoles.ROLE_USER;
    }

    public List<GrantedAuthority> resolveAuthorities(String oib) {
        return List.of(new SimpleGrantedAuthority(resolveRole(oib)));
    }
}
