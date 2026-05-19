package com.str.backend.auth;

import com.str.backend.lessor.LessorEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class LessorPrincipal implements UserDetails {

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_LESSOR"));

    private final UUID lessorId;
    private final String username;
    private final String passwordHash;

    public LessorPrincipal(LessorEntity entity) {
        this.lessorId = entity.getLessorId();
        this.username = entity.getUsername();
        this.passwordHash = entity.getPasswordHash();
    }

    public UUID getLessorId() {
        return lessorId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AUTHORITIES;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
