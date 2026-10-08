package com.str.backend.str;

import com.str.backend.lessor.LegalRepresentativeSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Zastupnik tvrtke po eTurizmu: {@code str.subject} (tvrtka) → {@code subject_version} →
 * {@code document} → {@code subject_representative_id} → {@code subject_version} (zastupnik).
 * Aktivan je uvijek, neovisno o OIB sustavu — eTurizam je jedini izvor tog podatka.
 *
 * <p>{@code pin} u eTurizmu nije nužno OIB (strana osoba ima drugi identifikator). Zastupnik bez
 * OIB-a ne može ni u {@code lessor.representative_oib} (CHECK na 11 znamenki) ni u OIB sustav, pa se
 * tretira kao da ga eTurizam ne navodi — zastupnik je tada NIAS osoba.
 */
@Component
@Transactional(readOnly = true)
public class StrLegalRepresentativeSource implements LegalRepresentativeSource {

    private static final Pattern OIB = Pattern.compile("\\d{11}");

    private final StrSubjectRepository subjectRepository;

    public StrLegalRepresentativeSource(StrSubjectRepository subjectRepository) {
        this.subjectRepository = subjectRepository;
    }

    @Override
    public Optional<Representative> findRepresentative(String legalOib, String preferredOib) {
        return subjectRepository.findRepresentativeByLegalOib(legalOib, preferredOib)
                .map(r -> new Representative(r.getOib().trim(), r.getFirstName(), r.getLastName()))
                .filter(r -> OIB.matcher(r.oib()).matches());
    }
}
