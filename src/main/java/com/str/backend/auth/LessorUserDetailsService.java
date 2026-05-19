package com.str.backend.auth;

import com.str.backend.lessor.LessorRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessorUserDetailsService implements UserDetailsService {

    private final LessorRepository repository;

    public LessorUserDetailsService(LessorRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return repository.findByUsername(username)
                .filter(l -> l.getPasswordHash() != null)
                .map(LessorPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Iznajmljivač s korisničkim imenom '" + username + "' nije pronađen."));
    }
}
