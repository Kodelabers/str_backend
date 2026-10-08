package com.str.backend.lessor;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import com.str.backend.address.CountyByMunicipalityResolver;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Stavka 2: identitet iz NIAS-a, adresa iz registra (OIB sustav ili str.subject). U ime tvrtke
 * (T8): MBS i sjedište iz OIB sustava, jedan zastupnik iz eTurizma s imenom i adresom iz OIB sustava.
 */
class SubjectProfileServiceTest {

    private static final String OIB = "12312312316";
    private static final String LEGAL_OIB = "45645645646";
    private static final String NIAS_OIB = "70000000004";
    private static final String OTHER_REP = "11111111119";

    private final SubjectRegistry registry = mock(SubjectRegistry.class);
    private final LegalEntityRegistry legalRegistry = mock(LegalEntityRegistry.class);
    private final LegalRepresentativeSource representativeSource = mock(LegalRepresentativeSource.class);
    private final CountyByMunicipalityResolver countyResolver = mock(CountyByMunicipalityResolver.class);
    private final CountryRepository countryRepository = mock(CountryRepository.class);
    private final SubjectProfileService service =
            new SubjectProfileService(registry, legalRegistry, representativeSource, countyResolver, countryRepository);

    /** ID Hrvatske namjerno nije onaj iz lokalnog seeda (7) — servis ga mora pročitati iz str.country. */
    private static final long CROATIA_ID = 191L;

    private void croatiaInCountryTable() {
        CountryEntity croatia = mock(CountryEntity.class);
        when(croatia.getId()).thenReturn(CROATIA_ID);
        when(countryRepository.findFirstByIso2AlphaIgnoreCaseOrderByIdAsc("HR")).thenReturn(Optional.of(croatia));
    }

    private static RegistrySubject oibRegistrySubject() {
        return new RegistrySubject(OIB, "PERO", "PERIĆ", null, "Ilica", "1", "Zagreb", "10000",
                "ZAGREB", null, SubjectDataSource.OIB_REGISTAR);
    }

    private static RegistrySubject strSubject(String legalName) {
        return new RegistrySubject(OIB, "Pero", "Perić", legalName, "Ilica", "1", "Zagreb", null,
                null, "Grad Zagreb", SubjectDataSource.STR_SUBJEKT);
    }

    @Test
    void niasNameWins_overRegistryName() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(oibRegistrySubject()));

        SubjectProfile p = service.load(OIB, " Pero ", "Perić");

        assertThat(p.firstName()).isEqualTo("Pero");
        assertThat(p.lastName()).isEqualTo("Perić");
        assertThat(p.nameSource()).isEqualTo(SubjectDataSource.NIAS);
    }

    /** local/mock nema SAML assertiona — tada je ime iz registra, i to se vidi u izvoru. */
    @Test
    void registryName_usedOnlyWhenNiasHasNone() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(strSubject(null)));

        SubjectProfile p = service.load(OIB, null, " ");

        assertThat(p.firstName()).isEqualTo("Pero");
        assertThat(p.nameSource()).isEqualTo(SubjectDataSource.STR_SUBJEKT);
    }

    /** OIB je uvijek onaj iz sesije — registar (str.subject_version.pin) ga ne smije zamijeniti. */
    @Test
    void oib_isAlwaysTheSessionOib() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(new RegistrySubject("99999999990",
                "Pero", "Perić", null, null, null, null, null, null, null, SubjectDataSource.STR_SUBJEKT)));

        assertThat(service.load(OIB, "Pero", "Perić").oib()).isEqualTo(OIB);
    }

    /**
     * OIB sustav ne vraća županiju, a o njoj ovisi GO-1 (status domaćina) — izvodi se iz općine.
     */
    @Test
    void county_derivedFromMunicipality_whenRegistryHasNone() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(oibRegistrySubject()));
        when(countyResolver.countyOf("ZAGREB")).thenReturn(Optional.of("Grad Zagreb"));

        SubjectProfile p = service.load(OIB, "Pero", "Perić");

        assertThat(p.county()).isEqualTo("Grad Zagreb");
        assertThat(p.municipality()).isEqualTo("ZAGREB");
        assertThat(p.addressSource()).isEqualTo(SubjectDataSource.OIB_REGISTAR);
    }

    @Test
    void county_fromRegistry_isNotOverridden() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(strSubject(null)));

        SubjectProfile p = service.load(OIB, "Pero", "Perić");

        assertThat(p.county()).isEqualTo("Grad Zagreb");
        verify(countyResolver, never()).countyOf(any());
    }

    @Test
    void unknownOib_isBusinessError_notOutage() {
        when(registry.findByOib(OIB)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.load(OIB, "Pero", "Perić"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.subject.notFound");
    }

    @Test
    void registryOutage_propagates() {
        when(registry.findByOib(OIB)).thenThrow(new ExternalRegistryException("OIB", "down"));

        assertThatThrownBy(() -> service.load(OIB, "Pero", "Perić"))
                .isInstanceOf(ExternalRegistryException.class);
    }

    @Test
    void toLessor_takesIdentityAndAddress() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(strSubject(null)));

        LessorEntity lessor = service.resolveLessor(OIB, "Pero", "Perić");

        assertThat(lessor.getLessorOib()).isEqualTo(OIB);
        assertThat(lessor.getFirstName()).isEqualTo("Pero");
        assertThat(lessor.getStreet()).isEqualTo("Ilica");
        assertThat(lessor.getStreetNumber()).isEqualTo("1");
        assertThat(lessor.getPlace()).isEqualTo("Zagreb");
        assertThat(lessor.getCounty()).isEqualTo("Grad Zagreb");
        assertThat(lessor.getEmail()).isNull();
        assertThat(lessor.getLegalEntityName()).isNull();
    }

    // ── u ime tvrtke (T8, Uredba 2024/1028 čl. 5(1)(c)) ─────────────────────

    private static RegistryLegalEntity company(String mbs) {
        return new RegistryLegalEntity(LEGAL_OIB, "PERINA TESTNA FIRMA D.O.O", mbs, "ULICA CVIJETE ZUZORIĆ",
                "3", "ZAGREB", "10000", "GRAD ZAGREB", null, SubjectDataSource.OIB_REGISTAR);
    }

    private static RegistrySubject person(String oib) {
        return new RegistrySubject(oib, "PERO", "PERIĆ", null, "SJENJAK", "19", "OSIJEK", "31000",
                "OSIJEK", null, SubjectDataSource.OIB_REGISTAR);
    }

    /** Svih šest podataka iz uredbe osim kontakta (on je iz str.document_contact, v. NiasController). */
    @Test
    void loadLegal_seatAndMbsFromRegistry_representativeNameAndAddressFromRegistry() {
        when(legalRegistry.findLegalEntity(LEGAL_OIB)).thenReturn(Optional.of(company("080123456")));
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).thenReturn(Optional.empty());
        when(legalRegistry.findPerson(NIAS_OIB)).thenReturn(Optional.of(person(NIAS_OIB)));
        when(countyResolver.countyOf("GRAD ZAGREB")).thenReturn(Optional.of("Grad Zagreb"));
        when(countyResolver.countyOf("OSIJEK")).thenReturn(Optional.of("Osječko-baranjska"));

        LegalEntityProfile p = service.loadLegal(LEGAL_OIB, "TESTNA TVRTKA d.o.o.", NIAS_OIB, "Ana", "Horvat");

        assertThat(p.name()).isEqualTo("TESTNA TVRTKA d.o.o.");
        assertThat(p.registrationNumber()).isEqualTo("080123456");
        assertThat(p.street()).isEqualTo("ULICA CVIJETE ZUZORIĆ");
        assertThat(p.postalCode()).isEqualTo("10000");
        assertThat(p.municipality()).isEqualTo("GRAD ZAGREB");
        assertThat(p.county()).isEqualTo("Grad Zagreb");
        assertThat(p.seatSource()).isEqualTo(SubjectDataSource.OIB_REGISTAR);

        SubjectProfile r = p.representative();
        assertThat(r.oib()).isEqualTo(NIAS_OIB);
        assertThat(r.firstName()).isEqualTo("PERO");
        assertThat(r.nameSource()).isEqualTo(SubjectDataSource.OIB_REGISTAR);
        assertThat(r.street()).isEqualTo("SJENJAK");
        assertThat(r.county()).isEqualTo("Osječko-baranjska");
        assertThat(r.addressSource()).isEqualTo(SubjectDataSource.OIB_REGISTAR);
    }

    /** Koji je zastupnik zna eTurizam; NIAS osoba se predaje kao prednost, ne kao odluka. */
    @Test
    void loadLegal_representativeChosenByStr_withNiasPersonPreferred() {
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB))
                .thenReturn(Optional.of(new LegalRepresentativeSource.Representative(OTHER_REP, "Iva", "Ivić")));
        when(legalRegistry.findPerson(OTHER_REP)).thenReturn(Optional.of(person(OTHER_REP)));

        SubjectProfile r = service.loadLegal(LEGAL_OIB, "T", NIAS_OIB, "Ana", "Horvat").representative();

        assertThat(r.oib()).isEqualTo(OTHER_REP);
        assertThat(r.firstName()).isEqualTo("PERO");
        verify(legalRegistry).findPerson(OTHER_REP);
        verify(legalRegistry, never()).findPerson(NIAS_OIB);
    }

    /** Kućna adresa drugog zastupnika (npr. bivšeg direktora) ne ide podnositelju ni u lessor. */
    @Test
    void loadLegal_otherRepresentative_addressIsNotExposed() {
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB))
                .thenReturn(Optional.of(new LegalRepresentativeSource.Representative(OTHER_REP, "Iva", "Ivić")));
        when(legalRegistry.findPerson(OTHER_REP)).thenReturn(Optional.of(person(OTHER_REP)));

        LessorEntity lessor = service.resolveLegalLessor(LEGAL_OIB, "T", NIAS_OIB, "Ana", "Horvat");
        SubjectProfile r = service.loadLegal(LEGAL_OIB, "T", NIAS_OIB, "Ana", "Horvat").representative();

        assertThat(r.street()).isNull();
        assertThat(r.place()).isNull();
        assertThat(r.addressSource()).isNull();
        assertThat(lessor.getRepresentativeAddress()).isNull();
        assertThat(lessor.getRepresentativeOib()).isEqualTo(OTHER_REP);
    }

    /** Vrijednosti iz registara ne smiju prekoračiti stupce lessora — INSERT bi srušio izdavanje RB-a. */
    @Test
    void toLegalLessor_clipsOverlongRegistryValues() {
        String longStreet = "U".repeat(600);
        when(legalRegistry.findLegalEntity(LEGAL_OIB)).thenReturn(Optional.of(new RegistryLegalEntity(LEGAL_OIB,
                "T", "M".repeat(60), longStreet, "1234567890123456789", "P".repeat(200), "10000", null,
                "Ž".repeat(200), SubjectDataSource.OIB_REGISTAR)));
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).thenReturn(Optional.empty());
        when(legalRegistry.findPerson(NIAS_OIB)).thenReturn(Optional.of(new RegistrySubject(NIAS_OIB,
                "I".repeat(200), "P".repeat(200), null, longStreet, "1", "Osijek", "31000", null, null,
                SubjectDataSource.OIB_REGISTAR)));

        LessorEntity lessor = service.resolveLegalLessor(LEGAL_OIB, "T", NIAS_OIB, "Ana", "Horvat");

        assertThat(lessor.getStreet()).hasSize(500);
        assertThat(lessor.getStreetNumber()).hasSize(16);
        assertThat(lessor.getPlace()).hasSize(128);
        assertThat(lessor.getCounty()).hasSize(128);
        assertThat(lessor.getFirstName()).hasSize(128);
        assertThat(lessor.getLegalEntityRegistrationNumber()).hasSize(40);
        assertThat(lessor.getRepresentativeAddress()).hasSize(255);
        assertThat(lessor.getLegalRepresentativeName()).hasSize(255);
    }

    /** Nepoznato ime zastupnika je „nema podatka" (null), a ne prazan tekst. */
    @Test
    void toLegalLessor_unknownRepresentativeName_isNull() {
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).thenReturn(Optional.empty());

        LessorEntity lessor = service.resolveLegalLessor(LEGAL_OIB, "T", NIAS_OIB, null, null);

        assertThat(lessor.getLegalRepresentativeName()).isNull();
        assertThat(lessor.getFirstName()).isEqualTo("N/A");
    }

    @Test
    void loadLegal_otherRepresentativeUnknownToRegistry_usesStrName() {
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB))
                .thenReturn(Optional.of(new LegalRepresentativeSource.Representative(OTHER_REP, "Iva", "Ivić")));
        when(legalRegistry.findPerson(OTHER_REP)).thenReturn(Optional.empty());

        SubjectProfile r = service.loadLegal(LEGAL_OIB, "T", NIAS_OIB, "Ana", "Horvat").representative();

        assertThat(r.firstName()).isEqualTo("Iva");
        assertThat(r.nameSource()).isEqualTo(SubjectDataSource.STR_SUBJEKT);
        assertThat(r.street()).isNull();
        assertThat(r.addressSource()).isNull();
    }

    /** Nedostupan OIB sustav ne smije spriječiti ni prikaz forme ni izdavanje RB-a. */
    @Test
    void loadLegal_registryUnavailable_leavesDataMissing_withoutFailing() {
        when(legalRegistry.findLegalEntity(LEGAL_OIB)).thenThrow(new ExternalRegistryException("OIB", "down"));
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).thenReturn(Optional.empty());
        when(legalRegistry.findPerson(NIAS_OIB)).thenThrow(new ExternalRegistryException("OIB", "down"));

        LegalEntityProfile p = service.loadLegal(LEGAL_OIB, "TESTNA TVRTKA d.o.o.", NIAS_OIB, "Ana", "Horvat");

        assertThat(p.registrationNumber()).isNull();
        assertThat(p.street()).isNull();
        assertThat(p.seatSource()).isNull();
        assertThat(p.representative().firstName()).isEqualTo("Ana");
        assertThat(p.representative().nameSource()).isEqualTo(SubjectDataSource.NIAS);
        assertThat(p.representative().addressSource()).isNull();
    }

    /**
     * e-Zastupanja: iznajmljivač je tvrtka (lessorOib) sa sjedištem i MBS-om; zastupnik i njegova
     * adresa idu u zasebne stupce. Sa sjedištem je poznata i županija, pa GO-1 radi.
     */
    @Test
    void toLegalLessor_storesSeatMbsAndRepresentative() {
        when(legalRegistry.findLegalEntity(LEGAL_OIB)).thenReturn(Optional.of(company("080123456")));
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).thenReturn(Optional.empty());
        when(legalRegistry.findPerson(NIAS_OIB)).thenReturn(Optional.of(person(NIAS_OIB)));
        when(countyResolver.countyOf("GRAD ZAGREB")).thenReturn(Optional.of("Grad Zagreb"));

        LessorEntity lessor = service.resolveLegalLessor(LEGAL_OIB, "TESTNA TVRTKA d.o.o.", NIAS_OIB, "Ana", "Horvat");

        assertThat(lessor.getLessorOib()).isEqualTo(LEGAL_OIB);
        assertThat(lessor.isLegalEntityOwner()).isTrue();
        assertThat(lessor.getLegalEntityName()).isEqualTo("TESTNA TVRTKA d.o.o.");
        assertThat(lessor.getLegalEntityRegistrationNumber()).isEqualTo("080123456");
        assertThat(lessor.getStreet()).isEqualTo("ULICA CVIJETE ZUZORIĆ");
        assertThat(lessor.getStreetNumber()).isEqualTo("3");
        assertThat(lessor.getPlace()).isEqualTo("ZAGREB");
        assertThat(lessor.getCounty()).isEqualTo("Grad Zagreb");
        assertThat(lessor.getRepresentativeOib()).isEqualTo(NIAS_OIB);
        assertThat(lessor.getLegalRepresentativeName()).isEqualTo("PERO PERIĆ");
        assertThat(lessor.getRepresentativeAddress()).isEqualTo("SJENJAK 19, 31000 OSIJEK");
        assertThat(lessor.getFirstName()).isEqualTo("PERO");
        verify(registry, never()).findByOib(any());
    }

    @Test
    void toLegalLessor_withoutSeat_keepsAddressEmpty() {
        when(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).thenReturn(Optional.empty());

        LessorEntity lessor = service.resolveLegalLessor(LEGAL_OIB, "TESTNA TVRTKA d.o.o.", NIAS_OIB, "Ana", "Horvat");

        assertThat(lessor.getStreet()).isEmpty();
        assertThat(lessor.getCounty()).isEmpty();
        assertThat(lessor.getLegalEntityRegistrationNumber()).isNull();
        assertThat(lessor.getRepresentativeAddress()).isNull();
        assertThat(lessor.getRepresentativeOib()).isEqualTo(NIAS_OIB);
        assertThat(lessor.getLegalRepresentativeName()).isEqualTo("Ana Horvat");
    }

    /** Tok fizičke osobe ne smije dirati registar tvrtki. */
    @Test
    void naturalPerson_doesNotUseLegalEntityRegistry() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(strSubject(null)));

        service.load(OIB, "Pero", "Perić");

        verifyNoInteractions(legalRegistry, representativeSource);
    }

    /** Isto ponašanje kao raniji StrLessorLookupService: naziv subjekta ide na lessor. */
    @Test
    void toLessor_keepsLegalEntityName_fromStrSubject() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(strSubject("Adria d.o.o.")));

        assertThat(service.resolveLessor(OIB, "Pero", "Perić").getLegalEntityName()).isEqualTo("Adria d.o.o.");
    }

    /** lessor.street je NOT NULL — registar bez adrese ne smije srušiti izdavanje RB-a. */
    @Test
    void toLessor_toleratesMissingAddress() {
        when(registry.findByOib(OIB)).thenReturn(Optional.of(new RegistrySubject(OIB, null, null, null,
                null, null, null, null, null, null, SubjectDataSource.OIB_REGISTAR)));

        LessorEntity lessor = service.resolveLessor(OIB, "Pero", "Perić");

        assertThat(lessor.getStreet()).isEmpty();
        assertThat(lessor.getCounty()).isEmpty();
    }

    // ── država prebivališta („Zemlja” na NIAS profilu) ─────────────────────

    @Test
    void toLessor_naturalPerson_livesInCroatia() {
        croatiaInCountryTable();
        when(registry.findByOib(OIB)).thenReturn(Optional.of(strSubject(null)));

        LessorEntity lessor = service.resolveLessor(OIB, "Pero", "Perić");

        assertThat(lessor.getCountryOfResidenceId()).isEqualTo((int) CROATIA_ID);
    }

    @Test
    void toLegalLessor_company_livesInCroatia() {
        croatiaInCountryTable();

        LessorEntity lessor = service.resolveLegalLessor(LEGAL_OIB, "TESTNA TVRTKA d.o.o.", NIAS_OIB, "Ana", "Horvat");

        assertThat(lessor.getCountryOfResidenceId()).isEqualTo((int) CROATIA_ID);
    }

    /** Šifrarnik bez Hrvatske ne smije srušiti izdavanje RB-a — iznajmljivač ostaje bez države. */
    @Test
    void croatiaMissingFromCountryTable_lessorIsStillBuilt() {
        when(countryRepository.findFirstByIso2AlphaIgnoreCaseOrderByIdAsc("HR")).thenReturn(Optional.empty());
        when(registry.findByOib(OIB)).thenReturn(Optional.of(strSubject(null)));

        LessorEntity lessor = service.resolveLessor(OIB, "Pero", "Perić");

        assertThat(lessor.getLessorOib()).isEqualTo(OIB);
        assertThat(lessor.getCountryOfResidenceId()).isNull();
    }
}
