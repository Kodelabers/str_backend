package com.str.backend.lessor;

import com.str.backend.address.CountyByMunicipalityResolver;
import com.str.backend.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * Jedini izvor podataka o fizičkoj osobi na NIAS putu: isti {@link #load} služi prefillu forme
 * ({@code GET /api/nias/subject}) i izdavanju RB-a, pa ono što korisnik vidi i ono što se spremi
 * ne mogu doći iz različitih izvora.
 *
 * <p>Pri izdavanju RB-a podaci se ponovo dohvaćaju na serveru — klijentu se ne vjeruje; iz
 * zahtjeva dolazi samo kontakt, koji je korisnik smio ispraviti. Registar je iza
 * {@link SubjectRegistry}: OIB sustav kad je uključen, inače {@code str.subject*}.
 *
 * <p>Prima vrijednosti, a ne NIAS tip, da {@code lessor} ne ovisi o {@code auth.nias}.
 *
 * <p>Pravna osoba (eOvlaštenja) još nije podržana: NIAS ne javlja da netko zastupa pravnu osobu.
 */
@Service
public class SubjectProfileService {

    /** Isti popunjivač kao u ranijem lookupu — {@code lessor.first_name/last_name} su NOT NULL. */
    private static final String MISSING = "N/A";

    private final SubjectRegistry subjectRegistry;
    private final CountyByMunicipalityResolver countyResolver;

    public SubjectProfileService(SubjectRegistry subjectRegistry,
                                 CountyByMunicipalityResolver countyResolver) {
        this.subjectRegistry = subjectRegistry;
        this.countyResolver = countyResolver;
    }

    /**
     * @param oib           OIB iz sesije (NIAS assertion ili, na local/mock, konfigurirani mock OIB)
     * @param niasFirstName ime iz assertiona; {@code null} kad assertiona nema
     * @param niasLastName  prezime iz assertiona; {@code null} kad assertiona nema
     * @throws BusinessException {@code error.subject.notFound} kad ga registar ne poznaje (400)
     * @throws com.str.backend.exception.ExternalRegistryException kad registar nije dostupan (503)
     */
    public SubjectProfile load(String oib, String niasFirstName, String niasLastName) {
        RegistrySubject subject = subjectRegistry.findByOib(oib)
                .orElseThrow(() -> new BusinessException("error.subject.notFound"));

        boolean niasHasName = known(niasFirstName) && known(niasLastName);
        // Županija je potrebna za GO-1 (status domaćina). OIB sustav je ne vraća, pa se izvodi
        // iz općine; str.subject je daje izravno.
        String county = known(subject.county())
                ? subject.county()
                : countyResolver.countyOf(subject.municipality()).orElse(null);
        return new SubjectProfile(
                oib,
                niasHasName ? niasFirstName.trim() : orMissing(subject.firstName()),
                niasHasName ? niasLastName.trim() : orMissing(subject.lastName()),
                niasHasName ? SubjectDataSource.NIAS : subject.source(),
                subject.legalEntityName(),
                subject.street(),
                subject.streetNumber(),
                subject.place(),
                subject.postalCode(),
                subject.municipality(),
                county,
                subject.source());
    }

    /**
     * Još nepohranjeni iznajmljivač. Kontakt se dopisuje iz zahtjeva
     * ({@link LessorEntity#applyContact}) prije prve pohrane, jer je {@code email}
     * {@code updatable = false}.
     */
    public LessorEntity toLessor(SubjectProfile profile) {
        LessorEntity lessor = LessorEntity.create(
                profile.firstName(),
                profile.lastName(),
                orEmpty(profile.street()),
                orEmpty(profile.streetNumber()),
                orEmpty(profile.place()),
                orEmpty(profile.county()),
                null);
        lessor.setLessorOib(profile.oib());
        if (known(profile.legalEntityName())) {
            lessor.setLegalEntityName(profile.legalEntityName());
        }
        return lessor;
    }

    public LessorEntity resolveLessor(String oib, String niasFirstName, String niasLastName) {
        return toLessor(load(oib, niasFirstName, niasLastName));
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank();
    }

    private static String orMissing(String value) {
        return known(value) ? value.trim() : MISSING;
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
