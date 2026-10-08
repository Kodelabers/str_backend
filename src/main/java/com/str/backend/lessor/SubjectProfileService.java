package com.str.backend.lessor;

import com.str.backend.address.CountryRepository;
import com.str.backend.address.CountyByMunicipalityResolver;
import com.str.backend.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Pravnu osobu (e-Zastupanja) gradi {@link #toLegalLessor}: podaci o tvrtki dolaze iz
 * e-Ovlaštenja, a ne iz ovog registra.
 */
@Service
public class SubjectProfileService {

    private static final Logger log = LoggerFactory.getLogger(SubjectProfileService.class);

    /** Isti popunjivač kao u ranijem lookupu — {@code lessor.first_name/last_name} su NOT NULL. */
    private static final String MISSING = "N/A";

    /**
     * Država NIAS iznajmljivača. Fizička osoba dolazi iz hrvatskog registra (OIB sustav ili
     * {@code str.subject*}), tvrtka iz e-Ovlaštenja — obje su hrvatske. Isti kod koristi changeset 134.
     */
    private static final String CROATIA_ISO2 = "HR";

    private final SubjectRegistry subjectRegistry;
    private final CountyByMunicipalityResolver countyResolver;
    private final CountryRepository countryRepository;

    public SubjectProfileService(SubjectRegistry subjectRegistry,
                                 CountyByMunicipalityResolver countyResolver,
                                 CountryRepository countryRepository) {
        this.subjectRegistry = subjectRegistry;
        this.countyResolver = countyResolver;
        this.countryRepository = countryRepository;
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
        applyCroatia(lessor);
        return lessor;
    }

    /**
     * Iznajmljivač je pravna osoba koju NIAS osoba zastupa (e-Zastupanja): {@code lessorOib} je
     * OIB tvrtke, a ime i prezime su zastupnikovi ({@code lessor.first_name/last_name} su NOT NULL).
     *
     * <p>Adresa sjedišta tvrtke nije poznata — e-Ovlaštenja je ne daju — pa ostaje prazna. Time je
     * i GO-1 (status domaćina) {@code false}; je li to ispravno za pravnu osobu, poslovno je pitanje.
     */
    public LessorEntity toLegalLessor(String legalOib, String legalName, String representativeOib,
                                      String representativeFirstName, String representativeLastName) {
        LessorEntity lessor = LessorEntity.create(
                orMissing(representativeFirstName),
                orMissing(representativeLastName),
                "", "", "", "", null);
        lessor.setLessorOib(legalOib);
        lessor.applyNiasLegalEntity(legalName, representativeOib,
                (orEmpty(representativeFirstName) + " " + orEmpty(representativeLastName)).trim());
        applyCroatia(lessor);
        return lessor;
    }

    public LessorEntity resolveLessor(String oib, String niasFirstName, String niasLastName) {
        return toLessor(load(oib, niasFirstName, niasLastName));
    }

    /**
     * Država se traži po ISO kodu, ne po ID-u: {@code str.country} je vanjska tablica i ID ne mora biti
     * isti na svim okolinama. Kad Hrvatske u tablici nema, iznajmljivač se ipak sprema — bez
     * države profil ostaje bez „Zemlje”, ali izdavanje RB-a ne smije pasti zbog šifrarnika.
     */
    private void applyCroatia(LessorEntity lessor) {
        countryRepository.findFirstByIso2AlphaIgnoreCaseOrderByIdAsc(CROATIA_ISO2)
                .map(country -> Math.toIntExact(country.getId()))
                .ifPresentOrElse(lessor::applyCountryOfResidence,
                        () -> log.warn("str.country nema državu {} — iznajmljivač {} ostaje bez države prebivališta",
                                CROATIA_ISO2, lessor.getLessorId()));
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
