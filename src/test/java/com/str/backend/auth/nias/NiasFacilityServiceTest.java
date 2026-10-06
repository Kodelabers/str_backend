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
import com.str.backend.str.StrFacilityRepository.ListingTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
        stubFacilities(row(153049L, "uuid-a", true, "Soba 1", "FS_SOBA", "Soba", null, 2));

        FacilityPageResponse page = service.list(OIB, null, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.totalUnits()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(NiasFacilityService.DEFAULT_PAGE_SIZE);
        FacilityResponse item = page.items().getFirst();
        assertThat(item.id()).isEqualTo("153049");
        assertThat(item.vrstaSifra()).isEqualTo("FS_SOBA");
        assertThat(item.brKreveta()).isEqualTo(2);
        assertThat(item.registracijskiBroj()).isNull();
        assertThat(item.izvor()).isEqualTo(FacilitySource.ETURIZAM);
        assertThat(item.objektId()).isEqualTo("uuid-a");
        assertThat(item.verificiran()).isTrue();
        // ukupno nosi redak stranice — zaseban count se ne pita
        verify(facilityRepository, never()).countListingByOib(anyString(), any());
    }

    /** Neverificirani (migrirani) objekt nosi zastavicu, da ga frontend označi. */
    @Test
    void mapsUnverifiedFlag() {
        stubFacilities(row(153049L, "uuid-m", false, "Soba 1", "FS_SOBA", "Soba", null, 2));

        assertThat(service.list(OIB, 0, 20).items().getFirst().verificiran()).isFalse();
    }

    /**
     * Write-back RB-a u str.facility je best-effort — kad padne, RB je samo na našoj strani.
     * Bez ovog fallbacka objekt s izdanim RB-om izgledao bi kao da ga nema.
     */
    @Test
    void fallsBackToOwnRegistrationNumber_whenWriteBackMissing() {
        stubFacilities(row(153049L, "uuid-a", true, "Soba 1", "FS_SOBA", "Soba", null, 2));
        // mock se gradi prije when(...) — ugniježđeno stubiranje Mockito odbija
        FacilityRnRow ourRn = rnRow("153049", "HR100000000000000001");
        when(rnRepository.findRnsByFacilityIds(List.of("153049"))).thenReturn(List.of(ourRn));

        FacilityPageResponse page = service.list(OIB, 0, 20);

        assertThat(page.items().getFirst().registracijskiBroj()).isEqualTo("HR100000000000000001");
    }

    @Test
    void prefersEturizamRegistrationNumber_andSkipsOwnLookup() {
        stubFacilities(row(153049L, "uuid-a", true, "Soba 1", "FS_SOBA", "Soba", "HR100000000000000009", 2));

        FacilityPageResponse page = service.list(OIB, 0, 20);

        assertThat(page.items().getFirst().registracijskiBroj()).isEqualTo("HR100000000000000009");
        verify(rnRepository, never()).findRnsByFacilityIds(any());
    }

    /**
     * FacilityResponse je record s pozicijskim komponentama, pa pin na mapiranje privremenog
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
        stubTotals(0, 0);

        FacilityResponse item = service.list(OIB, 0, 20).items().getFirst();

        assertThat(item.id()).isEqualTo(decision.getDecisionId().toString());
        assertThat(item.naziv()).isEqualTo("Soba Marija");
        assertThat(item.vrstaSifra()).isEqualTo("FS_SOBA");
        assertThat(item.brKreveta()).isEqualTo(3);
        assertThat(item.punaAdresa()).isEqualTo("Kraljevska 88, Makarska");
        assertThat(item.registracijskiBroj()).isNull();
        assertThat(item.izvor()).isEqualTo(FacilitySource.PRIVREMENO_RJESENJE);
        // svako rješenje je zaseban objekt; nije u eTurizmu pa nije ni (ne)verificirano
        assertThat(item.objektId()).isEqualTo(decision.getDecisionId().toString());
        assertThat(item.verificiran()).isNull();
    }

    /** Bez unesenog naziva red se prikazuje pod nazivom datoteke — inače bi bio bezimen. */
    @Test
    void fallsBackToFileName_whenObjectNameMissing() {
        stubDecisions(decision("skan-rjesenja.pdf"));
        stubFacilities();
        stubTotals(0, 0);

        assertThat(service.list(OIB, 0, 20).items().getFirst().naziv()).isEqualTo("skan-rjesenja.pdf");
    }

    /** Redoslijed: eTurizam objekti (verificirani pa neverificirani, iz upita), zatim privremena rješenja. */
    @Test
    void putsTemporaryDecisionsAfterEturizam_andCountsThemInTotal() {
        stubDecisions(decision("Soba iz rjesenja.pdf"));
        stubFacilities(row(1L, "uuid-a", true, "Soba 1", "FS_SOBA", "Soba", null, 2, 1, 1));

        FacilityPageResponse page = service.list(OIB, 0, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalUnits()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst().izvor()).isEqualTo(FacilitySource.ETURIZAM);
        assertThat(page.items().get(1).izvor()).isEqualTo(FacilitySource.PRIVREMENO_RJESENJE);
        verify(facilityRepository).findListingByOib(OIB, CODES, 20, 0L);
    }

    /**
     * Stranica broji objekte, ne jedinice: tri jedinice dvaju objekata na stranici veličine 2
     * popunjavaju je, pa privremeno rješenje ide na sljedeću.
     */
    @Test
    void countsObjectsNotUnits_whenFillingPage() {
        stubDecisions(decision("Skan.pdf"));
        stubFacilities(
                row(1L, "uuid-a", true, "Apartman A1", "FS_APARTMAN", "Apartman", null, 2, 3, 4),
                row(2L, "uuid-a", true, "Apartman A2", "FS_APARTMAN", "Apartman", null, 2, 3, 4),
                row(3L, "uuid-b", false, "Soba B1", "FS_SOBA", "Soba", null, 2, 3, 4));

        FacilityPageResponse page = service.list(OIB, 0, 2);

        assertThat(page.items()).hasSize(3);
        assertThat(page.items()).extracting(FacilityResponse::izvor).containsOnly(FacilitySource.ETURIZAM);
        assertThat(page.items()).extracting(FacilityResponse::objektId)
                .containsExactly("uuid-a", "uuid-a", "uuid-b");
        assertThat(page.total()).isEqualTo(4);       // 3 eTurizam objekta + 1 rješenje
        assertThat(page.totalUnits()).isEqualTo(5);  // 4 eTurizam jedinice + 1 rješenje
    }

    /** Zadnja stranica eTurizma dopunjava se privremenim rješenjima od početka njihova popisa. */
    @Test
    void fillsLastEturizamPage_withTemporaryDecisions() {
        stubDecisions(decision("Skan 1"), decision("Skan 2"), decision("Skan 3"));
        stubFacilities(row(21L, "uuid-z", true, "Soba Z", "FS_SOBA", "Soba", null, 2, 21, 21));

        FacilityPageResponse page = service.list(OIB, 1, 20);

        // stranica 2 nosi 21. eTurizam objekt i prva 3 rješenja (ukupno 4 objekta < 20)
        assertThat(page.items()).extracting(FacilityResponse::izvor).containsExactly(
                FacilitySource.ETURIZAM, FacilitySource.PRIVREMENO_RJESENJE,
                FacilitySource.PRIVREMENO_RJESENJE, FacilitySource.PRIVREMENO_RJESENJE);
        assertThat(page.total()).isEqualTo(24);
        verify(facilityRepository).findListingByOib(OIB, CODES, 20, 20L);
    }

    /**
     * Stranica iza svih eTurizam objekata: upit vrati prazno, ukupno se pita zasebno, a rješenja
     * se nastavljaju od pomaka koji preostaje nakon eTurizma.
     */
    @Test
    void offsetsTemporaryDecisions_pastEturizamObjects() {
        stubDecisions(decision("Skan 1"), decision("Skan 2"), decision("Skan 3"));
        stubFacilities();
        stubTotals(10, 15);

        FacilityPageResponse page = service.list(OIB, 1, 10);

        // svih 10 eTurizam objekata je na prvoj stranici; druga počinje prvim rješenjem
        assertThat(page.items()).hasSize(3);
        assertThat(page.total()).isEqualTo(13);
        assertThat(page.totalUnits()).isEqualTo(18);
    }

    @Test
    void skipsTemporaryDecisionsAlreadyShown_onLaterPages() {
        stubDecisions(decision("Skan 1"), decision("Skan 2"), decision("Skan 3"));
        stubFacilities();
        stubTotals(8, 8);

        FacilityPageResponse page = service.list(OIB, 2, 5);

        // pomak 10: 8 eTurizam objekata + 2 rješenja su na prethodnim stranicama → ostaje treće
        assertThat(page.items()).extracting(FacilityResponse::naziv).containsExactly("Skan 3");
        assertThat(page.total()).isEqualTo(11);
    }

    /** page * size je long — inače bi veliki page prelio int u negativan OFFSET i oborio query. */
    @Test
    void survivesHugePageNumber_withoutNegativeOffset() {
        stubFacilities();
        stubTotals(5, 7);

        FacilityPageResponse page = service.list(OIB, Integer.MAX_VALUE, 100);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(5);
        verify(facilityRepository).findListingByOib(OIB, CODES, 100, (long) Integer.MAX_VALUE * 100);
    }

    @Test
    void clampsPageSize_andNormalisesNegativePage() {
        stubFacilities();
        stubTotals(0, 0);

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
        verify(facilityRepository, never()).findListingByOib(anyString(), any(), anyInt(), anyLong());
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
        when(row.getCurrent()).thenReturn(true);
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
        when(row.getCurrent()).thenReturn(true);
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
        when(row.getCurrent()).thenReturn(true);
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

    /**
     * Vlastita jedinica koja nije aktualna (npr. stara verzija ili migrirana kopija koju je
     * zamijenio noviji predmet): popis je ne prikazuje, pa je ni tuStart handoff ne smije otvoriti.
     */
    @Test
    void claim_rejectsRowThatIsNotCurrent() {
        FacilityOwnershipRow row = activeOwnRow();
        when(row.getCurrent()).thenReturn(false);

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
        when(facilityRepository.findListingByOib(eq(OIB), eq(CODES), anyInt(), anyLong()))
                .thenReturn(List.of(rows));
    }

    /** Ukupno za praznu stranicu — kad ga ne nosi nijedan redak. */
    private void stubTotals(long objects, long units) {
        ListingTotals totals = mock(ListingTotals.class);
        when(totals.getObjects()).thenReturn(objects);
        when(totals.getUnits()).thenReturn(units);
        when(facilityRepository.countListingByOib(OIB, CODES)).thenReturn(totals);
    }

    private void stubDecisions(CategorizationDecisionEntity... decisions) {
        when(decisionRepository.findByLessorOibAndFacilityIdIsNullAndRnIsNullAndStatusNotOrderByUploadedAtDesc(
                OIB, CategorizationDecisionStatus.REJECTED)).thenReturn(List.of(decisions));
    }

    /** Jedinica jedinog objekta iznajmljivača (ukupno 1 objekt, 1 jedinica). */
    private static FacilityListingRow row(Long id, String systemUuid, boolean verified, String name,
                                          String subtypeCode, String subtypeName,
                                          String registrationNumber, Integer beds) {
        return row(id, systemUuid, verified, name, subtypeCode, subtypeName, registrationNumber, beds, 1, 1);
    }

    private static FacilityListingRow row(Long id, String systemUuid, boolean verified, String name,
                                          String subtypeCode, String subtypeName,
                                          String registrationNumber, Integer beds,
                                          long totalObjects, long totalUnits) {
        FacilityListingRow row = mock(FacilityListingRow.class);
        lenient().when(row.getFacilityId()).thenReturn(id);
        lenient().when(row.getSystemUuid()).thenReturn(systemUuid);
        lenient().when(row.getVerified()).thenReturn(verified);
        lenient().when(row.getTotalObjects()).thenReturn(totalObjects);
        lenient().when(row.getTotalUnits()).thenReturn(totalUnits);
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
