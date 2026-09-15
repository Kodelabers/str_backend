package com.str.backend.egop;

import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ugovor alokatora: urudžbeni broj je redni broj pismena <b>unutar predmeta</b> (podnesak 1,
 * obavijest o dodjeli 2), a redni broj predmeta se nastavlja na ono što je već u bazi.
 */
class LocalFilingNumberAllocatorTest {

    private SubmissionRepository submissions;
    private EgopPismenoRepository pismena;

    @BeforeEach
    void setUp() {
        submissions = mock(SubmissionRepository.class);
        pismena = mock(EgopPismenoRepository.class);
    }

    private LocalFilingNumberAllocator allocator(String prefix) {
        return new LocalFilingNumberAllocator(submissions, pismena, new MockEnvironment(), prefix, "334-01", "529-06");
    }

    @Test
    void urBroj_countsPerPredmet_podnesakIsOneAndAktIsTwo() {
        LocalFilingNumberAllocator allocator = allocator("MOCK-");
        when(pismena.countFiledInPredmet(2026, 7)).thenReturn(0L, 1L);

        assertThat(allocator.nextUrBroj(2026, 7).vrijednost()).isEqualTo("MOCK-529-06/26-1");
        assertThat(allocator.nextUrBroj(2026, 7).vrijednost()).isEqualTo("MOCK-529-06/26-2");
    }

    /** Svaki predmet kreće ispočetka — to je cijela poanta zahvata. */
    @Test
    void urBroj_newPredmet_restartsAtOne() {
        LocalFilingNumberAllocator allocator = allocator("MOCK-");
        when(pismena.countFiledInPredmet(2026, 7)).thenReturn(2L);
        when(pismena.countFiledInPredmet(2026, 8)).thenReturn(0L);

        assertThat(allocator.nextUrBroj(2026, 7).redni()).isEqualTo(3);
        assertThat(allocator.nextUrBroj(2026, 8).redni()).isEqualTo(1);
    }

    @Test
    void urBroj_emptyPrefix_looksLikeRealNumber() {
        when(pismena.countFiledInPredmet(anyInt(), anyInt())).thenReturn(0L);

        assertThat(allocator("").nextUrBroj(2026, 3).vrijednost()).isEqualTo("529-06/26-1");
    }

    /**
     * Sjeme iz baze je ono što drži {@code uq_submission_filing_number}: otkad svaki podnesak
     * nosi ur. br. 1, jedinstvenost te vrijednosti počiva samo na KLASI.
     */
    @Test
    void predmet_seedsFromDatabaseMax_andIncrements() {
        int godina = LocalDate.now().getYear();
        when(submissions.maxEgopRbrPredmeta(godina)).thenReturn(41);
        LocalFilingNumberAllocator allocator = allocator("MOCK-");

        LocalFilingNumberAllocator.Predmet prvi = allocator.nextPredmet();
        LocalFilingNumberAllocator.Predmet drugi = allocator.nextPredmet();

        assertThat(prvi.rbrPredmeta()).isEqualTo(42);
        assertThat(drugi.rbrPredmeta()).isEqualTo(43);
        assertThat(prvi.uredskaGodina()).isEqualTo(godina);
        assertThat(prvi.klasa()).isEqualTo("MOCK-334-01/" + (godina % 100) + "-01/42");
    }

    /** Sjeme se čita jednom po godini, ne pri svakom predmetu. */
    @Test
    void predmet_seedIsLazyAndCachedPerYear() {
        int godina = LocalDate.now().getYear();
        when(submissions.maxEgopRbrPredmeta(godina)).thenReturn(0);
        LocalFilingNumberAllocator allocator = allocator("MOCK-");

        allocator.nextPredmet();
        allocator.nextPredmet();

        org.mockito.Mockito.verify(submissions, org.mockito.Mockito.times(1))
                .maxEgopRbrPredmeta(godina);
    }

    /**
     * Prefiks je jedina oznaka po kojoj se izmišljena KLASA/URBROJ razlikuje od prave. Prazan je
     * dopušten na demo okolinama, ali na produkciji mora srušiti start.
     */
    @Test
    void emptyPrefix_onProdProfile_refusesToStart() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new LocalFilingNumberAllocator(submissions, pismena, prod, "", "334-01", "529-06"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void blankPrefix_onProdProfile_alsoRefusesToStart() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new LocalFilingNumberAllocator(submissions, pismena, prod, "   ", "334-01", "529-06"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mockPrefix_onProdProfile_isAllowed() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThat(new LocalFilingNumberAllocator(submissions, pismena, prod, "MOCK-", "334-01", "529-06").prefix())
                .isEqualTo("MOCK-");
    }

    @Test
    void emptyPrefix_onDemoProfile_isAllowed() {
        MockEnvironment cdu = new MockEnvironment();
        cdu.setActiveProfiles("cdu");

        assertThat(new LocalFilingNumberAllocator(submissions, pismena, cdu, "", "334-01", "529-06").prefix())
                .isEmpty();
    }

    @Test
    void klasa_isStableForGivenPredmet() {
        LocalFilingNumberAllocator allocator = allocator("MOCK-");

        assertThat(allocator.klasa(2025, 101)).isEqualTo("MOCK-334-01/25-01/101");
        assertThat(allocator.prefix()).isEqualTo("MOCK-");
    }
}
