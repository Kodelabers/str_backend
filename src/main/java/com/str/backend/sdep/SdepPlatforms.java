package com.str.backend.sdep;

import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.OnlinePlatformEntity;
import com.str.backend.lookup.OnlinePlatformRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platforma iz predaje SDEP-a, po nazivu bez razlike velikih i malih slova (T3).
 *
 * <p>Nepoznata platforma je 400, dok se ne dogovori registracija platformi. Samo na CDU testu
 * (profil {@code cdu}) STR je doda sam, da svaka platforma registrirana na SDEP portalu može
 * predati aktivnosti. Privremeno rješenje.
 */
@Component
class SdepPlatforms {

    private static final Logger log = LoggerFactory.getLogger(SdepPlatforms.class);

    private final OnlinePlatformRepository platforms;
    private final boolean autoCreate;

    SdepPlatforms(OnlinePlatformRepository platforms, Environment environment) {
        this.platforms = platforms;
        this.autoCreate = environment.acceptsProfiles(Profiles.of("cdu"));
    }

    /** Postojeća platforma ili, na CDU testu, nova; inače {@code error.sdep.platform.unknown}. */
    @Transactional
    public synchronized OnlinePlatformEntity resolve(String platformName, String requestId) {
        String name = platformName.strip();
        return platforms.findFirstByActiveTrueAndNameIgnoreCase(name).orElseGet(() -> {
            if (!autoCreate) {
                log.warn("sdep_ingest_unknown_platform zahtjev={} platforma='{}'", requestId, name);
                throw new BusinessException("error.sdep.platform.unknown");
            }
            OnlinePlatformEntity created = platforms.save(OnlinePlatformEntity.fromSdep(name));
            log.info("sdep_platform_created zahtjev={} platforma='{}' id={}", requestId, name,
                    created.getPlatformId());
            return created;
        });
    }
}
