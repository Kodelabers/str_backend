package com.str.backend.auth.nias;

import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnRepository.FacilityRnRow;
import com.str.backend.str.StrFacilityRepository;
import com.str.backend.str.StrFacilityRepository.FacilityListingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private final NiasFacilityService service = new NiasFacilityService(
            facilityRepository, typeRepository, rnRepository, decisionRepository);

    @BeforeEach
    void setUp() {
        lenient().when(typeRepository.findAllCodes()).thenReturn(CODES);
        lenient().when(decisionRepository
                .findByLessorOibAndFacilityIdIsNullAndStatusNotOrderByUploadedAtDesc(anyString(), any()))
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
                OIB, "skan.pdf", "application/pdf", new byte[]{1},
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

    private void stubFacilities(FacilityListingRow... rows) {
        when(facilityRepository.findListingByOib(eq(OIB), eq(CODES), anyInt(), anyInt()))
                .thenReturn(List.of(rows));
    }

    private void stubDecisions(CategorizationDecisionEntity... decisions) {
        when(decisionRepository.findByLessorOibAndFacilityIdIsNullAndStatusNotOrderByUploadedAtDesc(
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
        return CategorizationDecisionEntity.create("99999999990", fileName, "application/pdf",
                new byte[]{1, 2, 3},
                new CategorizationDecisionMetadata(null, null, null, null, null, null, null));
    }
}
