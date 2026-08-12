package com.str.backend.str;

import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnRepository.FacilityRnRow;
import com.str.backend.str.FacilityClaimVerifier.Claim;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Brana za zahtjev koji se poziva na postojeći eTurizam objekt. Vlasništvo je tu najvažnije:
 * bez njega se tuđim {@code facilityId}-em RB write-backom upiše u tuđi zapis u eTurizmu.
 */
class FacilityClaimVerifierTest {

    private static final String OIB = "06756460531";

    private final StrFacilityRepository facilityRepository = mock(StrFacilityRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final FacilityClaimVerifier verifier =
            new FacilityClaimVerifier(facilityRepository, typeRepository, rnRepository);

    /** Zahtjev koji ni u čemu ne odstupa od stubanog objekta. */
    private static Claim claim(long typeId, int beds) {
        return new Claim(typeId, beds, null, null, null, null, null, null);
    }

    @Test
    void skips_whenNoFacilityId() {
        assertThatCode(() -> verifier.verify(OIB, null, claim(1L, 2))).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(OIB, "  ", claim(1L, 2))).doesNotThrowAnyException();
        verifyNoInteractions(facilityRepository);
    }

    @Test
    void rejects_whenFacilityIdNotNumeric() {
        assertThatThrownBy(() -> verifier.verify(OIB, "abc", claim(1L, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.unknown");
        verifyNoInteractions(facilityRepository);
    }

    @Test
    void rejects_whenFacilityMissing() {
        when(facilityRepository.findOwnership(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", claim(1L, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.unknown");
    }

    @Test
    void rejects_whenFacilityBelongsToAnotherLessor() {
        stubFacility("12312312316", "FS_SOBA", 2, true);

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", claim(1L, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.notOwned");
    }

    @Test
    void rejects_whenFacilityInactive() {
        stubFacility(OIB, "FS_SOBA", 2, false);

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", claim(1L, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.inactive");
    }

    /**
     * Dva RB-a za isti eTurizam objekt: adresni {@code checkDuplicateLocation} to promaši jer su
     * ulica i kućni broj u eTurizmu najčešće prazni, a write-back drugog RB-a bi tiho pao na
     * {@code WHERE registration_number IS NULL}.
     */
    @Test
    void rejects_whenFacilityAlreadyHasStandingRn() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        FacilityRnRow existing = mock(FacilityRnRow.class);
        when(rnRepository.findRnsByFacilityIds(List.of("153049"))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", claim(1L, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.alreadyRegistered");
    }

    @Test
    void rejects_whenTypeChanged() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_APARTMAN");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", claim(1L, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.type.mismatch");
    }

    @Test
    void rejects_whenBedCountChanged() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", claim(1L, 5)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.beds.mismatch");
    }

    @Test
    void passes_whenTypeAndBedsMatch() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "fs_soba"); // sifra se usporeduje neosjetljivo na velika/mala slova

        assertThatCode(() -> verifier.verify(OIB, " 153049 ", claim(1L, 2))).doesNotThrowAnyException();
    }

    /** eTurizam bez kategoriziranog kapaciteta ne smije obarati zahtjev. */
    @Test
    void passes_whenEturizamHasNoCapacity() {
        stubFacility(OIB, "FS_SOBA", null, true);
        stubSubmittedType(1L, "FS_SOBA");

        assertThatCode(() -> verifier.verify(OIB, "153049", claim(1L, 4))).doesNotThrowAnyException();
    }

    /** Vrsta bez FS_ šifre (npr. hotel) — usporedba se preskače, ne laže. */
    @Test
    void passes_whenSubmittedTypeHasNoCode() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        AccommodationTypeEntity type = new AccommodationTypeEntity("Hotel", false, "Hoteli");
        assertThat(type.getCode()).isNull();
        when(typeRepository.findById(9L)).thenReturn(Optional.of(type));

        assertThatCode(() -> verifier.verify(OIB, "153049", claim(9L, 2))).doesNotThrowAnyException();
    }

    // --- Naziv i adresa: primjedba s UAT-a da se gornji podaci ne smiju mijenjati ---

    @Test
    void rejects_whenNameChanged() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubName("Apartman Marija");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, "Apartman Ivana", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.name.mismatch");
    }

    @Test
    void passes_whenNameDiffersOnlyByCaseAndSpacing() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubName("Apartman  Marija");

        assertThatCode(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, " apartman marija ", null, null, null, null, null)))
                .doesNotThrowAnyException();
    }

    /**
     * {@code -} je u {@code str.facility.name} uobičajeni popunjivač. Zaključati ga značilo bi
     * zabraniti korisniku da upiše stvarni naziv objekta, pa se broji kao nepoznato.
     */
    @Test
    void passes_whenEturizamNameIsPlaceholder() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubName("-");

        assertThatCode(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, "Villa Ana", null, null, null, null, null)))
                .doesNotThrowAnyException();
    }

    /**
     * 11,5 % objekata na CDU nosi ime vlasnika umjesto naziva objekta (27.912 od 242.468).
     * Zaključati to značilo bi da vlasnik ne može upisati stvarni naziv.
     */
    @Test
    void passes_whenEturizamNameIsTheLessorsOwnName() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubName("Tonći Beroš");
        when(stubbedRow.getOwnerFullName()).thenReturn("Tonći Beroš");

        assertThatCode(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, "Villa Makarska", null, null, null, null, null)))
                .doesNotThrowAnyException();
        assertThat(FacilityClaimVerifier.lockedFields(stubbedRow)).doesNotContain("name");
    }

    /** Isto i kad je ime na `subject_version.name` (pravna osoba). */
    @Test
    void passes_whenEturizamNameIsTheLegalEntityName() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubName("  beroš d.o.o. ");
        when(stubbedRow.getOwnerName()).thenReturn("Beroš d.o.o.");

        assertThatCode(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, "Villa Makarska", null, null, null, null, null)))
                .doesNotThrowAnyException();
    }

    /** Naziv koji je vrsta smještaja ostaje zaključan — to je vrijednost koju eTurizam vodi. */
    @Test
    void locksGenericTypeWordName_whichIsNotTheLessorsName() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubName("Apartman");
        when(stubbedRow.getOwnerFullName()).thenReturn("Tonći Beroš");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, "Villa Makarska", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.name.mismatch");
    }

    /**
     * Popunjivač se prepoznaje po pravilu „nema ni slova ni znamenke", ne po popisu — inače bi
     * svaki novi oblik (`--`, `.`) prošao kao stvarni naziv i zaključao polje.
     */
    @Test
    void treatsAnyPunctuationOnlyNameAsMissing() {
        for (String placeholder : new String[]{"-", "--", "—", ".", "...", "   "}) {
            stubFacility(OIB, "FS_SOBA", 2, true);
            stubSubmittedType(1L, "FS_SOBA");
            stubName(placeholder);

            assertThatCode(() -> verifier.verify(OIB, "153049",
                    new Claim(1L, 2, "Villa Ana", null, null, null, null, null)))
                    .describedAs("popunjivac '%s'", placeholder)
                    .doesNotThrowAnyException();
            assertThat(FacilityClaimVerifier.objectName(stubbedRow)).isNull();
        }
    }

    @Test
    void rejects_whenStreetChanged() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubAddress("Splitsko-dalmatinska", "Makarska", "Makarska", "Kalalarga", "12");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, null, "Splitsko-dalmatinska", "Makarska", "Makarska", "Ilica", "12")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.address.mismatch");
    }

    @Test
    void rejects_whenCountyChanged() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubAddress("Splitsko-dalmatinska", "Makarska", "Makarska", "Kalalarga", "12");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, null, "Grad Zagreb", "Makarska", "Makarska", "Kalalarga", "12")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.address.mismatch");
    }

    @Test
    void passes_whenAddressMatches() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubAddress("Splitsko-dalmatinska", "Makarska", "Makarska", "Kalalarga", "12");

        assertThatCode(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, null, "Splitsko-dalmatinska", "Makarska", "Makarska", "Kalalarga", "12")))
                .doesNotThrowAnyException();
    }

    /**
     * Adrese u {@code str.address} su rijetko strukturirane (ulica popunjena u 217 od 285.874
     * redaka). Prazan izvor ne smije oboriti zahtjev — inače bi legitiman handoff dobio 400.
     */
    @Test
    void passes_whenEturizamHasNoStructuredAddress() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");

        assertThatCode(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, "Villa Ana", "Splitsko-dalmatinska", "Makarska", "Makarska", "Ilica", "1")))
                .doesNotThrowAnyException();
    }

    // --- Popis zaključanih polja koji ide frontendu ---

    @Test
    void lockedFields_listsOnlyWhatEturizamKnows() {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getSubtypeCode()).thenReturn("FS_SOBA");
        when(row.getBeds()).thenReturn(2);
        when(row.getName()).thenReturn("-");                 // popunjivač → nije zaključano
        when(row.getCountyName()).thenReturn("Splitsko-dalmatinska");
        when(row.getMunicipalityName()).thenReturn("Makarska");
        when(row.getSettlementName()).thenReturn(null);      // nepoznato → nije zaključano
        when(row.getStreetName()).thenReturn("  ");          // prazno → nije zaključano
        when(row.getHouseNumber()).thenReturn("12");

        assertThat(FacilityClaimVerifier.lockedFields(row))
                .containsExactly("typeId", "maxBeds", "countyId", "cityId", "streetNumber");
    }

    /** Popis i provjera moraju se slagati: polje koje nije zaključano smije se poslati izmijenjeno. */
    @Test
    void lockedFields_agreesWithVerify() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");
        stubName("-");
        FacilityOwnershipRow row = facilityRepository.findOwnership(153049L).orElseThrow();

        assertThat(FacilityClaimVerifier.lockedFields(row)).doesNotContain("name");
        assertThatCode(() -> verifier.verify(OIB, "153049",
                new Claim(1L, 2, "Novi naziv", null, null, null, null, null)))
                .doesNotThrowAnyException();
    }

    private FacilityOwnershipRow stubbedRow;

    private void stubFacility(String oib, String subtypeCode, Integer beds, boolean active) {
        stubbedRow = mock(FacilityOwnershipRow.class);
        when(stubbedRow.getOib()).thenReturn(oib);
        when(stubbedRow.getSubtypeCode()).thenReturn(subtypeCode);
        when(stubbedRow.getBeds()).thenReturn(beds);
        when(stubbedRow.getActive()).thenReturn(active);
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.of(stubbedRow));
    }

    private void stubName(String name) {
        when(stubbedRow.getName()).thenReturn(name);
    }

    private void stubAddress(String county, String municipality, String settlement,
                             String street, String houseNumber) {
        when(stubbedRow.getCountyName()).thenReturn(county);
        when(stubbedRow.getMunicipalityName()).thenReturn(municipality);
        when(stubbedRow.getSettlementName()).thenReturn(settlement);
        when(stubbedRow.getStreetName()).thenReturn(street);
        when(stubbedRow.getHouseNumber()).thenReturn(houseNumber);
    }

    private void stubSubmittedType(long typeId, String code) {
        AccommodationTypeEntity type = mock(AccommodationTypeEntity.class);
        when(type.getCode()).thenReturn(code);
        when(typeRepository.findById(typeId)).thenReturn(Optional.of(type));
    }
}
