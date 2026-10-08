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
import static org.mockito.Mockito.when;

/** Stavka 2: identitet iz NIAS-a, adresa iz registra (OIB sustav ili str.subject). */
class SubjectProfileServiceTest {

    private static final String OIB = "12312312316";

    private final SubjectRegistry registry = mock(SubjectRegistry.class);
    private final CountyByMunicipalityResolver countyResolver = mock(CountyByMunicipalityResolver.class);
    private final CountryRepository countryRepository = mock(CountryRepository.class);
    private final SubjectProfileService service =
            new SubjectProfileService(registry, countyResolver, countryRepository);

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

    /**
     * e-Zastupanja: iznajmljivač je tvrtka (lessorOib), a NIAS osoba je zastupnik. Registar se ne
     * pita — tvrtka nije fizička osoba iz str.subject.
     */
    @Test
    void toLegalLessor_companyIsLessor_personIsRepresentative() {
        LessorEntity lessor = service.toLegalLessor("33333333360", "TESTNA TVRTKA d.o.o.",
                "70000000004", "Ana", "Horvat");

        assertThat(lessor.getLessorOib()).isEqualTo("33333333360");
        assertThat(lessor.isLegalEntityOwner()).isTrue();
        assertThat(lessor.getLegalEntityName()).isEqualTo("TESTNA TVRTKA d.o.o.");
        assertThat(lessor.getRepresentativeOib()).isEqualTo("70000000004");
        assertThat(lessor.getLegalRepresentativeName()).isEqualTo("Ana Horvat");
        assertThat(lessor.getFirstName()).isEqualTo("Ana");
        assertThat(lessor.getStreet()).isEmpty();
        verify(registry, never()).findByOib(any());
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

        LessorEntity lessor = service.toLegalLessor("33333333360", "TESTNA TVRTKA d.o.o.",
                "70000000004", "Ana", "Horvat");

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
