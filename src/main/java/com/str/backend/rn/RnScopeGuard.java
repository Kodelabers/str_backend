package com.str.backend.rn;

import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.exception.ResourceNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Pojedinačni RB u opsegu službenika: RB smještaja iz druge županije županijskom službeniku je
 * nepostojeći (404), kao tuđi RB vlasniku — endpoint ne otkriva da postoji. Za cijelu RH (i za
 * svakoga tko nije službenik) ne radi ništa; vlasništvo provjerava pozivatelj.
 */
@Component
public class RnScopeGuard {

    private final CountyScopeService countyScope;
    private final RnRepository rnRepository;

    public RnScopeGuard(CountyScopeService countyScope, RnRepository rnRepository) {
        this.countyScope = countyScope;
        this.rnRepository = rnRepository;
    }

    public void requireInScope(String rn, Authentication authentication) {
        CountyScope scope = countyScope.scopeFor(authentication);
        if (scope.restricted() && !scope.covers(rnRepository.findCountyByRn(rn).orElse(null))) {
            throw new ResourceNotFoundException("rn not found: " + rn);
        }
    }
}
