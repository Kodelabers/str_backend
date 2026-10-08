package com.str.backend.lessor;

import com.str.backend.address.CountryRepository;
import com.str.backend.address.CountyByMunicipalityResolver;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.LegalRepresentativeSource.Representative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>Pravnu osobu (e-Zastupanja) na isti način opisuje {@link #loadLegal}, a gradi
 * {@link #toLegalLessor}: OIB i naziv tvrtke su iz e-Ovlaštenja, MBS i sjedište iz OIB sustava
 * ({@link LegalEntityRegistry}), a zastupnik iz eTurizma ({@link LegalRepresentativeSource}).
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

    /** Duljine stupaca {@code str_rn.lessor} (v. {@link LessorEntity}) za vrijednosti iz vanjskih registara. */
    private static final int NAME_MAX = 128;
    private static final int STREET_MAX = 500;
    private static final int STREET_NUMBER_MAX = 16;
    private static final int REGISTRATION_NUMBER_MAX = 40;
    private static final int TEXT_MAX = 255;

    private final SubjectRegistry subjectRegistry;
    private final LegalEntityRegistry legalEntityRegistry;
    private final LegalRepresentativeSource representativeSource;
    private final CountyByMunicipalityResolver countyResolver;
    private final CountryRepository countryRepository;

    public SubjectProfileService(SubjectRegistry subjectRegistry,
                                 LegalEntityRegistry legalEntityRegistry,
                                 LegalRepresentativeSource representativeSource,
                                 CountyByMunicipalityResolver countyResolver,
                                 CountryRepository countryRepository) {
        this.subjectRegistry = subjectRegistry;
        this.legalEntityRegistry = legalEntityRegistry;
        this.representativeSource = representativeSource;
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
        String county = countyOf(subject.county(), subject.municipality());
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
     * Tvrtka u čije ime NIAS osoba djeluje (e-Zastupanja), s točno jednim zastupnikom.
     *
     * <p>Ništa ovdje ne baca zbog registra: nedostupan ili isključen OIB sustav znači samo da
     * sjedište, MBS ili adresa zastupnika ostaju prazni — RB se izdaje i bez njih, a forma to kaže
     * napomenom.
     *
     * @param legalOib      OIB tvrtke iz potvrđenog zastupanja
     * @param legalName     naziv tvrtke iz e-Ovlaštenja
     * @param niasOib       OIB NIAS osobe; ona je zastupnik kad eTurizam ne zna drugog
     * @param niasFirstName ime iz assertiona; {@code null} kad assertiona nema
     * @param niasLastName  prezime iz assertiona; {@code null} kad assertiona nema
     */
    public LegalEntityProfile loadLegal(String legalOib, String legalName,
                                        String niasOib, String niasFirstName, String niasLastName) {
        RegistryLegalEntity company =
                quietly("company", () -> legalEntityRegistry.findLegalEntity(legalOib)).orElse(null);
        return new LegalEntityProfile(
                legalOib,
                legalName,
                company == null ? null : company.registrationNumber(),
                company == null ? null : company.street(),
                company == null ? null : company.streetNumber(),
                company == null ? null : company.place(),
                company == null ? null : company.postalCode(),
                company == null ? null : company.municipality(),
                company == null ? null : countyOf(company.county(), company.municipality()),
                company == null ? null : company.source(),
                loadRepresentative(legalOib, niasOib, niasFirstName, niasLastName));
    }

    /**
     * Ime i adresa zastupnika su iz OIB sustava — isti registar daje oboje, pa ime ima prednost i
     * pred NIAS-om. Kad ga sustav ne vrati: ime NIAS osobe ako je ona zastupnik, inače ime kako ga
     * vodi eTurizam. Adresa samo za NIAS osobu (v. niže).
     */
    private SubjectProfile loadRepresentative(String legalOib, String niasOib,
                                              String niasFirstName, String niasLastName) {
        Optional<Representative> listed = representativeSource.findRepresentative(legalOib, niasOib);
        String oib = listed.map(Representative::oib).orElse(niasOib);
        RegistrySubject person = quietly("representative", () -> legalEntityRegistry.findPerson(oib)).orElse(null);

        String firstName = null;
        String lastName = null;
        SubjectDataSource nameSource = null;
        if (person != null && known(person.firstName()) && known(person.lastName())) {
            firstName = person.firstName();
            lastName = person.lastName();
            nameSource = person.source();
        } else if (oib.equals(niasOib) && known(niasFirstName) && known(niasLastName)) {
            firstName = niasFirstName.trim();
            lastName = niasLastName.trim();
            nameSource = SubjectDataSource.NIAS;
        } else if (listed.isPresent() && known(listed.get().firstName()) && known(listed.get().lastName())) {
            firstName = listed.get().firstName().trim();
            lastName = listed.get().lastName().trim();
            nameSource = SubjectDataSource.STR_SUBJEKT;
        }

        // Kućna adresa je podatak o osobi, a ne o tvrtki: prikazuje se i sprema samo za NIAS osobu
        // koja zahtjev podnosi. Za drugog zastupnika iz eTurizma (npr. bivšeg direktora sa starog
        // dokumenta) podnositelj vidi samo ime — njegovo prebivalište nije mu potrebno.
        boolean hasAddress = oib.equals(niasOib)
                && person != null && (known(person.street()) || known(person.place()));
        return new SubjectProfile(
                oib,
                firstName,
                lastName,
                nameSource,
                null,
                hasAddress ? person.street() : null,
                hasAddress ? person.streetNumber() : null,
                hasAddress ? person.place() : null,
                hasAddress ? person.postalCode() : null,
                hasAddress ? person.municipality() : null,
                hasAddress ? countyOf(person.county(), person.municipality()) : null,
                hasAddress ? person.source() : null);
    }

    /**
     * Iznajmljivač je pravna osoba koju NIAS osoba zastupa (e-Zastupanja): {@code lessorOib} je
     * OIB tvrtke, adresa je sjedište, a ime i prezime su zastupnikovi
     * ({@code lessor.first_name/last_name} su NOT NULL).
     *
     * <p>Uz poznato sjedište poznata je i županija, pa GO-1 (status domaćina) uspoređuje županiju
     * sjedišta i objekta. Bez sjedišta adresa ostaje prazna i GO-1 je {@code false}.
     *
     * <p>Vrijednosti iz vanjskih registara režu se na duljinu stupca: preduga ulica ne smije srušiti
     * INSERT i s njim izdavanje RB-a.
     */
    public LessorEntity toLegalLessor(LegalEntityProfile profile) {
        SubjectProfile representative = profile.representative();
        LessorEntity lessor = LessorEntity.create(
                clip(orMissing(representative.firstName()), NAME_MAX),
                clip(orMissing(representative.lastName()), NAME_MAX),
                clip(orEmpty(profile.street()), STREET_MAX),
                clip(orEmpty(profile.streetNumber()), STREET_NUMBER_MAX),
                clip(orEmpty(profile.place()), NAME_MAX),
                clip(orEmpty(profile.county()), NAME_MAX),
                null);
        lessor.setLessorOib(profile.oib());
        String representativeName = joinKnown(" ", representative.firstName(), representative.lastName());
        lessor.applyNiasLegalEntity(profile.name(),
                clip(profile.registrationNumber(), REGISTRATION_NUMBER_MAX),
                representative.oib(),
                representativeName.isEmpty() ? null : clip(representativeName, TEXT_MAX),
                clip(formatAddress(representative), TEXT_MAX));
        applyCroatia(lessor);
        return lessor;
    }

    public LessorEntity resolveLegalLessor(String legalOib, String legalName,
                                           String niasOib, String niasFirstName, String niasLastName) {
        return toLegalLessor(loadLegal(legalOib, legalName, niasOib, niasFirstName, niasLastName));
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

    /** „ULICA 3, 10000 ZAGREB" — {@code lessor.representative_address} je jedan tekst. */
    static String formatAddress(SubjectProfile p) {
        String address = joinKnown(", ",
                joinKnown(" ", p.street(), p.streetNumber()),
                joinKnown(" ", p.postalCode(), p.place()));
        return address.isEmpty() ? null : address;
    }

    private String countyOf(String county, String municipality) {
        return known(county) ? county : countyResolver.countyOf(municipality).orElse(null);
    }

    /** Nedostupan OIB sustav → podatak nepoznat. U log ide samo što se tražilo, bez OIB-a. */
    private static <T> Optional<T> quietly(String lookup, Supplier<Optional<T>> call) {
        try {
            return call.get();
        } catch (ExternalRegistryException e) {
            log.warn("legal_entity_lookup_unavailable lookup={} error={}", lookup, e.getMessage());
            return Optional.empty();
        }
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String joinKnown(String separator, String... parts) {
        return Stream.of(parts).filter(SubjectProfileService::known).map(String::trim)
                .collect(Collectors.joining(separator));
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
