package com.str.backend.auth.nias;

import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnRepository.FacilityRnRow;
import com.str.backend.str.StrFacilityRepository;
import com.str.backend.str.StrFacilityRepository.FacilityListingRow;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NiasFacilityServiceTest {

    private static final String OIB = "99999999990";
    private static final List<String> CODES =
            List.of("FS_SOBA", "FS_APARTMAN", "FS_STUDIO_APARTMAN", "FS_KUCA_ZA_ODMOR");

    private final StrFacilityRepository facilityRepository = mock(StrFacilityRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final CategorizationDecisionRepository decisionRepository = mock(CategorizationDecisionRepository.class);

    private static final String ETURIZAM_BASE = "https://et2-test-external-eturizam.gov.hr";

    private final NiasFacilityService service = new NiasFacilityService(
            facilityRepository, typeRepository, rnRepository, decisionRepository, ETURIZAM_BASE);

    @BeforeEach
    void setUp() {
        lenient().when(typeRepository.findAllCodes()).thenReturn(CODES);
        lenient().when(decisionRepository
                .findByLessorOibAndFacilityIdIsNullAndRnIsNullAndStatusNotOrderByUploadedAtDesc(anyString(), any()))
                .thenReturn(List.of());
        lenient().when(rnRepository.findRnsByFacilityIds(any())).thenReturn(List.of());
    }

    @Test
    void maps_eturizamRowToResponse() {
        stubFacilities(row(153049L, "Soba 1", "FS_SOBA", "Soba", null, 2));
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(1L);

        FacilityPageResponse page = service.list(OIB, null, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(NiasFacilityService.DEFAULT_PAGE_SIZE);
        FacilityResponse item = page.items().getFirst();
        assertThat(item.id()).isEqualTo("153049");
        assertThat(item.vrstaSifra()).isEqualTo("FS_SOBA");
        assertThat(item.brKreveta()).isEqualTo(2);
        assertThat(item.registracijskiBroj()).isNull();
        assertThat(item.izvor()).isEqualTo(FacilitySource.ETURIZAM);
    }

    /**
     * Write-back RB-a u str.facility je best-effort — kad padne, RB je samo na našoj strani.
     * Bez ovog fallbacka objekt s izdanim RB-om izgledao bi kao da ga nema.
     */
    @Test
    void fallsBackToOwnRegistrationNumber_whenWriteBackMissing() {
        stubFacilities(row(153049L, "Soba 1", "FS_SOBA", "Soba", null, 2));
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(1L);
        // mock se gradi prije when(...) — ugniježđeno stubiranje Mockito odbija
        FacilityRnRow ourRn = rnRow("153049", "HR100000000000000001");
        when(rnRepository.findRnsByFacilityIds(List.of("153049"))).thenReturn(List.of(ourRn));

        FacilityPageResponse page = service.list(OIB, 0, 20);

        assertThat(page.items().getFirst().registracijskiBroj()).isEqualTo("HR100000000000000001");
    }

    @Test
    void prefersEturizamRegistrationNumber_andSkipsOwnLookup() {
        stubFacilities(row(153049L, "Soba 1", "FS_SOBA", "Soba", "HR100000000000000009", 2));
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(1L);

        FacilityPageResponse page = service.list(OIB, 0, 20);

        assertThat(page.items().getFirst().registracijskiBroj()).isEqualTo("HR100000000000000009");
        verify(rnRepository, never()).findRnsByFacilityIds(any());
    }

    /**
     * FacilityResponse je record sa 17 pozicijskih komponenti, pa pin na mapiranje privremenog
     * zapisa: umetanje novog polja koje pomakne redoslijed mora oboriti test, ne tiho zamijeniti
     * adresu i naziv u odgovoru.
     */
    @Test
    void mapsTemporaryDecisionFields() {
        CategorizationDecisionEntity decision = CategorizationDecisionEntity.create(
                OIB, null, "skan.pdf", "application/pdf", new byte[]{1},
                new CategorizationDecisionMetadata("Soba Marija", "FS_SOBA",
                        "Kraljevska 88, Makarska", "UP/I-334-01/26", null, 3, null));
        stubDecisions(decision);
        stubFacilities();
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(0L);

        FacilityResponse item = service.list(OIB, 0, 20).items().getFirst();

        assertThat(item.id()).isEqualTo(decision.getDecisionId().toString());
        assertThat(item.naziv()).isEqualTo("Soba Marija");
        assertThat(item.vrstaSifra()).isEqualTo("FS_SOBA");
        assertThat(item.brKreveta()).isEqualTo(3);
        assertThat(item.punaAdresa()).isEqualTo("Kraljevska 88, Makarska");
        assertThat(item.registracijskiBroj()).isNull();
        assertThat(item.izvor()).isEqualTo(FacilitySource.PRIVREMENO_RJESENJE);
    }

    /** Bez unesenog naziva red se prikazuje pod nazivom datoteke — inače bi bio bezimen. */
    @Test
    void fallsBackToFileName_whenObjectNameMissing() {
        stubDecisions(decision("skan-rjesenja.pdf"));
        stubFacilities();
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(0L);

        assertThat(service.list(OIB, 0, 20).items().getFirst().naziv()).isEqualTo("skan-rjesenja.pdf");
    }

    @Test
    void putsTemporaryDecisionsFirst_andCountsThemInTotal() {
        stubDecisions(decision("Soba iz rjesenja.pdf"));
        stubFacilities(row(1L, "Soba 1", "FS_SOBA", "Soba", null, 2));
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(3L);

        FacilityPageResponse page = service.list(OIB, 0, 20);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst().izvor()).isEqualTo(FacilitySource.PRIVREMENO_RJESENJE);
        assertThat(page.items().getFirst().registracijskiBroj()).isNull();
        assertThat(page.items().get(1).izvor()).isEqualTo(FacilitySource.ETURIZAM);
        // prva stranica trazi 19 eTurizam redaka jer je jedno mjesto zauzelo privremeno rjesenje
        verify(facilityRepository).findListingByOib(OIB, CODES, 19, 0);
    }

    /** Druga stranica ne smije preskočiti eTurizam redak zbog privremenog zapisa na prvoj. */
    @Test
    void offsetsEturizamByTemporaryCount_onLaterPages() {
        stubDecisions(decision("Skan 1"), decision("Skan 2"));
        stubFacilities();
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(50L);

        service.list(OIB, 1, 10);

        verify(facilityRepository).findListingByOib(OIB, CODES, 10, 8);
    }

    /** page * size je long — inače bi veliki page prelio int u negativan OFFSET i oborio query. */
    @Test
    void survivesHugePageNumber_withoutNegativeOffset() {
        stubFacilities();
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(5L);

        FacilityPageResponse page = service.list(OIB, Integer.MAX_VALUE, 100);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(5);
        verify(facilityRepository, never()).findListingByOib(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void clampsPageSize_andNormalisesNegativePage() {
        stubFacilities();
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(0L);

        FacilityPageResponse page = service.list(OIB, -5, 5000);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(NiasFacilityService.MAX_PAGE_SIZE);
    }

    /** Bez šifara u našem šifrarniku nema filtra, pa se eTurizam ne pita — IN () nije valjan SQL. */
    @Test
    void skipsEturizam_whenNoKnownCodes() {
        when(typeRepository.findAllCodes()).thenReturn(List.of());
        stubDecisions(decision("Skan.pdf"));

        FacilityPageResponse page = service.list(OIB, 0, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        verify(facilityRepository, never()).findListingByOib(anyString(), any(), anyInt(), anyInt());
        verify(facilityRepository, never()).countListingByOib(anyString(), any());
    }

    /** Claim nosi maksimalan broj gostiju (kreveti + pomoćni) i zaključava ga. */
    @Test
    void claim_returnsBedsPlusAuxiliaryAsMaxGuests() {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getOib()).thenReturn(OIB);
        when(row.getBeds()).thenReturn(4);
        when(row.getAuxiliaryBeds()).thenReturn(2);
        when(row.getActive()).thenReturn(true);
        when(row.getBusinessStatusCode()).thenReturn("FBS_ACTIVE");
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.of(row));

        FacilityClaimResponse claim = service.claim(OIB, "153049");

        assertThat(claim.brKreveta()).isEqualTo(6);
        assertThat(claim.zakljucanaPolja()).contains("maxBeds");
        // eTurizam vrstu ne zna — zabrana se ne može utemeljiti, RB se smije izdati.
        assertThat(claim.vrstaDopustena()).isTrue();
    }

    /** Claim nosi adresu eTurizmova zahtjeva za promjenu podataka: dokument objekta + id šifre. */
    @Test
    void claim_returnsChangeRequestUrl() {
        FacilityOwnershipRow row = activeOwnRow();
        when(row.getDocumentId()).thenReturn(892408L);
        when(facilityRepository.findCodebookElementId("DST_Z_PROMJ_POD")).thenReturn(Optional.of(454L));

        assertThat(service.claim(OIB, "153049").zahtjevPromjenaUrl()).isEqualTo(
                ETURIZAM_BASE + "/tu-start/podnesi-novi-zahtjev-za-promjenu-podataka/892408?idZahtjeva=454");
    }

    /** Bez dokumenta, šifre ili okoline eTurizma URL se ne slaže — forma tada ne nudi kvačicu. */
    @Test
    void claim_omitsChangeRequestUrl_whenItCannotBeBuilt() {
        FacilityOwnershipRow row = activeOwnRow();
        when(row.getDocumentId()).thenReturn(null);
        when(facilityRepository.findCodebookElementId("DST_Z_PROMJ_POD")).thenReturn(Optional.of(454L));
        assertThat(service.claim(OIB, "153049").zahtjevPromjenaUrl()).isNull();

        when(row.getDocumentId()).thenReturn(892408L);
        when(facilityRepository.findCodebookElementId("DST_Z_PROMJ_POD")).thenReturn(Optional.empty());
        assertThat(service.claim(OIB, "153049").zahtjevPromjenaUrl()).isNull();

        when(facilityRepository.findCodebookElementId("DST_Z_PROMJ_POD")).thenReturn(Optional.of(454L));
        NiasFacilityService bezEturizma = new NiasFacilityService(
                facilityRepository, typeRepository, rnRepository, decisionRepository, "");
        assertThat(bezEturizma.claim(OIB, "153049").zahtjevPromjenaUrl()).isNull();
    }

    /** Šifrarnik se ne mijenja: id se čita jednom, ne pri svakom claimu. */
    @Test
    void claim_readsChangeRequestTypeIdOnce() {
        FacilityOwnershipRow row = activeOwnRow();
        when(row.getDocumentId()).thenReturn(892408L);
        when(facilityRepository.findCodebookElementId("DST_Z_PROMJ_POD")).thenReturn(Optional.of(454L));

        service.claim(OIB, "153049");
        service.claim(OIB, "153049");

        verify(facilityRepository, times(1)).findCodebookElementId("DST_Z_PROMJ_POD");
    }

    @Test
    void changeRequestUrl_toleratesTrailingSlashInBase() {
        assertThat(NiasFacilityService.changeRequestUrl(ETURIZAM_BASE + "/", 1L, 2L))
                .isEqualTo(ETURIZAM_BASE + "/tu-start/podnesi-novi-zahtjev-za-promjenu-podataka/1?idZahtjeva=2");
    }

    private FacilityOwnershipRow activeOwnRow() {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getOib()).thenReturn(OIB);
        when(row.getActive()).thenReturn(true);
        when(row.getBusinessStatusCode()).thenReturn("FBS_ACTIVE");
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.of(row));
        return row;
    }

    /**
     * Vlastiti hotel ili restoran do forme može doći tuStart URL-om. Claim to kaže zastavicom, da
     * frontend blokira obrazac umjesto da ga korisnik ispuni i tek pri predaji dobije 400.
     */
    @Test
    void claim_flagsTypeThatCannotGetRn() {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getOib()).thenReturn(OIB);
        when(row.getActive()).thenReturn(true);
        when(row.getBusinessStatusCode()).thenReturn("FBS_ACTIVE");
        when(row.getSubtypeCode()).thenReturn("FS_HOTEL");
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.of(row));
        when(typeRepository.findByCodeIgnoreCase("FS_HOTEL")).thenReturn(Optional.empty());

        assertThat(service.claim(OIB, "153049").vrstaDopustena()).isFalse();
    }

    /**
     * Odjavljen objekt: forma se ne smije predpopuniti, jer bi submit pao na verifieru. Vlasniku
     * se to kaže (400), a ne 404 — objekt je njegov, pa ne otkriva ništa tuđe.
     */
    @Test
    void claim_rejectsFacilityThatIsNotActive() {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getOib()).thenReturn(OIB);
        when(row.getActive()).thenReturn(true);
        when(row.getBusinessStatusCode()).thenReturn("FBS_INACTIVE");
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.claim(OIB, "153049"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.inactive");
    }

    /** Tuđi odjavljeni objekt i dalje daje 404 — provjera statusa ide tek nakon vlasništva. */
    @Test
    void claim_returnsNotFound_forInactiveFacilityOfAnotherLessor() {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getOib()).thenReturn("12312312316");
        when(row.getBusinessStatusCode()).thenReturn("FBS_INACTIVE");
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.claim(OIB, "153049"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void stubFacilities(FacilityListingRow... rows) {
        when(facilityRepository.findListingByOib(eq(OIB), eq(CODES), anyInt(), anyInt()))
                .thenReturn(List.of(rows));
    }

    private void stubDecisions(CategorizationDecisionEntity... decisions) {
        when(decisionRepository.findByLessorOibAndFacilityIdIsNullAndRnIsNullAndStatusNotOrderByUploadedAtDesc(
                OIB, CategorizationDecisionStatus.REJECTED)).thenReturn(List.of(decisions));
    }

    private static FacilityListingRow row(Long id, String name, String subtypeCode, String subtypeName,
                                          String registrationNumber, Integer beds) {
        FacilityListingRow row = mock(FacilityListingRow.class);
        lenient().when(row.getFacilityId()).thenReturn(id);
        lenient().when(row.getName()).thenReturn(name);
        lenient().when(row.getSubtypeCode()).thenReturn(subtypeCode);
        lenient().when(row.getSubtypeName()).thenReturn(subtypeName);
        lenient().when(row.getRegistrationNumber()).thenReturn(registrationNumber);
        lenient().when(row.getBeds()).thenReturn(beds);
        return row;
    }

    private static FacilityRnRow rnRow(String facilityId, String rn) {
        FacilityRnRow row = mock(FacilityRnRow.class);
        lenient().when(row.getFacilityId()).thenReturn(facilityId);
        lenient().when(row.getRn()).thenReturn(rn);
        return row;
    }

    private static CategorizationDecisionEntity decision(String fileName) {
        return CategorizationDecisionEntity.create("99999999990", null, fileName, "application/pdf",
                new byte[]{1, 2, 3},
                new CategorizationDecisionMetadata(null, null, null, null, null, null, null));
    }
}
