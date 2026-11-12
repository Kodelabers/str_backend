package com.str.backend.attachment;

import com.str.backend.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrilogServiceTest {

    private static final String ANA = Ucitao.nias("12345678903");
    private static final String IVO = Ucitao.nias("98765432106");
    private static final byte[] PDF = "%PDF-1.7 sadrzaj".getBytes();

    private PrilogRepository repository;
    private PrilogStagingLock stagingLock;
    private PrilogService service;

    @BeforeEach
    void setUp() {
        repository = mock(PrilogRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Mali limit veličine i nevezanih, da testovi ne trebaju 10 MB.
        stagingLock = mock(PrilogStagingLock.class);
        service = new PrilogService(repository, stagingLock, 5, 6, DataSize.ofBytes(100), 3, DataSize.ofBytes(250),
                DataSize.ofBytes(150));
    }

    // --- stage -----------------------------------------------------------------------------

    @Test
    void stage_pdf_isStoredUnboundWithDetectedTypeAndHash() {
        PrilogResponse response = service.stage(ANA,
                new MockMultipartFile("datoteka", "C:\\put\\zapisnik.pdf", "image/png", PDF));

        ArgumentCaptor<PismenoPrilogEntity> saved = ArgumentCaptor.forClass(PismenoPrilogEntity.class);
        verify(repository).save(saved.capture());
        PismenoPrilogEntity e = saved.getValue();
        assertThat(e.getUcitao()).isEqualTo(ANA);
        assertThat(e.getNazivDatoteke()).isEqualTo("zapisnik.pdf");
        // Tip iz sadržaja, ne iz klijentova headera (image/png).
        assertThat(e.getContentType()).isEqualTo("application/pdf");
        assertThat(e.getVelicina()).isEqualTo(PDF.length);
        assertThat(e.getSha256()).hasSize(64).isEqualTo(PrilogService.sha256(PDF));
        assertThat(e.isVezan()).isFalse();
        assertThat(response.naziv()).isEqualTo("zapisnik.pdf");
        assertThat(response.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void stage_imagesAndWord_areAccepted() {
        service.stage(ANA, new MockMultipartFile("datoteka", "foto.jpg", null,
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}));
        service.stage(ANA, new MockMultipartFile("datoteka", "dopis.docx", null, new byte[]{0x50, 0x4B, 0x03, 0x04}));

        ArgumentCaptor<PismenoPrilogEntity> saved = ArgumentCaptor.forClass(PismenoPrilogEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PismenoPrilogEntity::getContentType)
                .containsExactly("image/jpeg", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void stage_empty_is400() {
        assertCode(() -> service.stage(ANA, new MockMultipartFile("datoteka", "a.pdf", null, new byte[0])),
                HttpStatus.BAD_REQUEST, PrilogException.EMPTY);
        assertCode(() -> service.stage(ANA, null), HttpStatus.BAD_REQUEST, PrilogException.EMPTY);
    }

    @Test
    void stage_tooLarge_is413() {
        byte[] velik = new byte[101];
        System.arraycopy(PDF, 0, velik, 0, 4);
        assertCode(() -> service.stage(ANA, new MockMultipartFile("datoteka", "a.pdf", null, velik)),
                HttpStatus.PAYLOAD_TOO_LARGE, PrilogException.TOO_LARGE);
        verify(repository, never()).save(any());
    }

    /** Tekst preimenovan u .pdf i ZIP preimenovan u .docx-u sličnu arhivu: odlučuje sadržaj. */
    @Test
    void stage_wrongContent_is415() {
        assertCode(() -> service.stage(ANA, new MockMultipartFile("datoteka", "lazni.pdf", "application/pdf",
                "nije pdf".getBytes())), HttpStatus.UNSUPPORTED_MEDIA_TYPE, PrilogException.TYPE);
        assertCode(() -> service.stage(ANA, new MockMultipartFile("datoteka", "arhiva.zip", null,
                new byte[]{0x50, 0x4B, 0x03, 0x04})), HttpStatus.UNSUPPORTED_MEDIA_TYPE, PrilogException.TYPE);
        verify(repository, never()).save(any());
    }

    @Test
    void stage_tooManyUnbound_is409() {
        when(repository.countByUcitaoAndVlasnikIdIsNull(ANA)).thenReturn(3L);

        assertCode(() -> service.stage(ANA, new MockMultipartFile("datoteka", "a.pdf", null, PDF)),
                HttpStatus.CONFLICT, PrilogException.STAGED_LIMIT);
        verify(repository, never()).save(any());
    }

    /** M-BE5: staging istog učitavatelja je serijaliziran prije prebrojavanja kvote. */
    @Test
    void stage_locksUploaderBeforeCountingQuota() {
        service.stage(ANA, new MockMultipartFile("datoteka", "a.pdf", null, PDF));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(stagingLock, repository);
        order.verify(stagingLock).zakljucaj(ANA);
        order.verify(repository).countByUcitaoAndVlasnikIdIsNull(ANA);
        order.verify(repository).sumVelicinaNevezanih(ANA);
        order.verify(repository).save(any());
    }

    /** Kvota ukupne veličine nevezanih (250 B u testu): 240 već učitano + 16 je preko. */
    @Test
    void stage_overTotalUnboundBytes_is409() {
        when(repository.sumVelicinaNevezanih(ANA)).thenReturn(240L);

        assertCode(() -> service.stage(ANA, new MockMultipartFile("datoteka", "a.pdf", null, PDF)),
                HttpStatus.CONFLICT, PrilogException.STAGED_LIMIT);
        verify(repository, never()).save(any());
    }

    /** L-names: ekstenzija naziva je ona koju je potvrdio sadržaj; osnovni naziv ostaje. */
    @Test
    void stage_rewritesExtensionToDetectedType_andStripsControlChars() {
        PrilogResponse lazna = service.stage(ANA, new MockMultipartFile("datoteka", "ugovor.exe", null, PDF));
        PrilogResponse jpeg = service.stage(ANA, new MockMultipartFile("datoteka", "foto.JPEG", null,
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}));
        PrilogResponse kontrolni = service.stage(ANA, new MockMultipartFile("datoteka",
                "ra\u010dun\r\n\u202Efdp.pdf", null, PDF));

        assertThat(lazna.naziv()).isEqualTo("ugovor.pdf");
        assertThat(jpeg.naziv()).isEqualTo("foto.JPEG");
        assertThat(kontrolni.naziv()).isEqualTo("ra\u010dunfdp.pdf");
    }

    // --- bind ------------------------------------------------------------------------------

    @Test
    void bind_ownUnbound_bindsAll() {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeci(List.of(1L, 2L)))
                .thenReturn(List.of(TestPrilog.nevezan(1, ANA), TestPrilog.nevezan(2, ANA)));
        when(repository.bind(any(), eq(ANA), eq(VrstaVlasnika.RN_LOG), eq(logId), any())).thenReturn(2);

        service.bind(ANA, List.of(1L, 2L, 1L), PrilogVlasnik.prijelaz(logId));

        verify(repository).bind(eq(List.of(1L, 2L)), eq(ANA),
                eq(VrstaVlasnika.RN_LOG), eq(logId), any());
    }

    @Test
    void bind_nullOrEmpty_doesNothing() {
        assertThat(service.bind(ANA, null, PrilogVlasnik.zahtjev(UUID.randomUUID()))).isEmpty();
        assertThat(service.bind(ANA, List.of(), PrilogVlasnik.zahtjev(UUID.randomUUID()))).isEmpty();
        verify(repository, never()).bind(any(), any(), any(), any(), any());
    }

    /** Tuđi prilog je 404, kao i nepostojeći — odgovor ne otkriva da id postoji. */
    @Test
    void bind_foreignAttachment_is404() {
        when(repository.findSazeci(List.of(1L))).thenReturn(List.of(TestPrilog.nevezan(1, IVO)));

        assertCode(() -> service.bind(ANA, List.of(1L), PrilogVlasnik.zahtjev(UUID.randomUUID())),
                HttpStatus.NOT_FOUND, PrilogException.NOT_FOUND);
        verify(repository, never()).bind(any(), any(), any(), any(), any());
    }

    /** Tvrtkin prilog (NIAS:<OIB tvrtke>) se ne veže uz radnju iste osobe „u svoje ime". */
    @Test
    void bind_companyAttachmentForPersonalAction_is404() {
        when(repository.findSazeci(List.of(1L))).thenReturn(List.of(TestPrilog.nevezan(1, Ucitao.nias("11111111117"))));

        assertCode(() -> service.bind(ANA, List.of(1L), PrilogVlasnik.zahtjev(UUID.randomUUID())),
                HttpStatus.NOT_FOUND, PrilogException.NOT_FOUND);
    }

    @Test
    void bind_unknownId_is404() {
        when(repository.findSazeci(List.of(1L, 2L))).thenReturn(List.of(TestPrilog.nevezan(1, ANA)));

        assertCode(() -> service.bind(ANA, List.of(1L, 2L), PrilogVlasnik.zahtjev(UUID.randomUUID())),
                HttpStatus.NOT_FOUND, PrilogException.NOT_FOUND);
    }

    @Test
    void bind_alreadyBound_is409() {
        UUID submissionId = UUID.randomUUID();
        when(repository.findSazeci(List.of(1L))).thenReturn(List.of(
                TestPrilog.vezan(1, VrstaVlasnika.SUBMISSION, submissionId, "a.pdf", ANA)));

        assertCode(() -> service.bind(ANA, List.of(1L), PrilogVlasnik.zahtjev(UUID.randomUUID())),
                HttpStatus.CONFLICT, PrilogException.ALREADY_BOUND);
    }

    /** Uvjetni UPDATE vezao je manje redaka: paralelna radnja je prilog u međuvremenu uzela. */
    @Test
    void bind_raceLost_is409() {
        when(repository.findSazeci(List.of(1L))).thenReturn(List.of(TestPrilog.nevezan(1, ANA)));
        when(repository.bind(any(), any(), any(), any(), any())).thenReturn(0);

        assertCode(() -> service.bind(ANA, List.of(1L), PrilogVlasnik.zahtjev(UUID.randomUUID())),
                HttpStatus.CONFLICT, PrilogException.ALREADY_BOUND);
    }

    @Test
    void bind_moreThanFive_is400() {
        assertCode(() -> service.bind(ANA, List.of(1L, 2L, 3L, 4L, 5L, 6L), PrilogVlasnik.zahtjev(UUID.randomUUID())),
                HttpStatus.BAD_REQUEST, PrilogException.COUNT);
        verify(repository, never()).findSazeci(anyCollection());
    }

    /** Ograničenje je po pismenu: zajedno s već vezanima uz istog vlasnika. */
    @Test
    void bind_togetherWithAlreadyBound_overFive_is400() {
        UUID submissionId = UUID.randomUUID();
        when(repository.findSazeci(List.of(1L, 2L))).thenReturn(List.of(TestPrilog.nevezan(1, ANA),
                TestPrilog.nevezan(2, ANA)));
        when(repository.countByVrstaVlasnikaAndVlasnikId(VrstaVlasnika.SUBMISSION, submissionId)).thenReturn(4L);

        assertCode(() -> service.bind(ANA, List.of(1L, 2L), PrilogVlasnik.zahtjev(submissionId)),
                HttpStatus.BAD_REQUEST, PrilogException.COUNT);
        verify(repository, never()).bind(any(), any(), any(), any(), any());
    }

    /**
     * Očitovanje i prigovor: dokument i do 5 priloga ({@code max-per-objection} = 6); ostala pismena i dalje
     * {@code max-per-owner} = 5.
     */
    @Test
    void requireBroj_objectionAllowsSix_othersFive() {
        service.requireBroj(VrstaVlasnika.OBJECTION, 6);
        assertCode(() -> service.requireBroj(VrstaVlasnika.OBJECTION, 7), HttpStatus.BAD_REQUEST, PrilogException.COUNT);
        service.requireBroj(VrstaVlasnika.SUBMISSION, 5);
        assertCode(() -> service.requireBroj(VrstaVlasnika.SUBMISSION, 6), HttpStatus.BAD_REQUEST, PrilogException.COUNT);
        assertCode(() -> service.requireBroj(VrstaVlasnika.RN_LOG, 6), HttpStatus.BAD_REQUEST, PrilogException.COUNT);
    }

    // --- delete / read ---------------------------------------------------------------------

    @Test
    void deleteStaged_own_deletes() {
        when(repository.findSazeci(List.of(1L))).thenReturn(List.of(TestPrilog.nevezan(1, ANA)));
        when(repository.deleteStaged(1L, ANA)).thenReturn(1);

        service.deleteStaged(ANA, 1L);

        verify(repository).deleteStaged(1L, ANA);
    }

    @Test
    void deleteStaged_foreignOrBound_isRefused() {
        when(repository.findSazeci(List.of(1L))).thenReturn(List.of(TestPrilog.nevezan(1, IVO)));
        assertCode(() -> service.deleteStaged(ANA, 1L), HttpStatus.NOT_FOUND, PrilogException.NOT_FOUND);

        when(repository.findSazeci(List.of(2L))).thenReturn(List.of(
                TestPrilog.vezan(2, VrstaVlasnika.SUBMISSION, UUID.randomUUID(), "a.pdf", ANA)));
        assertCode(() -> service.deleteStaged(ANA, 2L), HttpStatus.CONFLICT, PrilogException.ALREADY_BOUND);

        when(repository.findSazeci(List.of(3L))).thenReturn(List.of());
        assertCode(() -> service.deleteStaged(ANA, 3L), HttpStatus.NOT_FOUND, PrilogException.NOT_FOUND);
        verify(repository, never()).deleteStaged(anyLong(), any());
    }

    @Test
    void zaPreuzimanje_unboundOrOtherRn_is404() {
        PismenoPrilogEntity nevezan = PismenoPrilogEntity.staged(ANA, "a.pdf", "application/pdf", PDF, "x");
        when(repository.findById(1L)).thenReturn(Optional.of(nevezan));

        assertThatThrownBy(() -> service.zaPreuzimanje("HR180000123456789001", 1L))
                .isInstanceOf(ResourceNotFoundException.class);
        when(repository.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.zaPreuzimanje("HR180000123456789001", 2L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void pripadaPismenu_officialToOutgoing_partyToIncoming() {
        TestPrilog sluzbenik = TestPrilog.nevezan(1, "INTERNAL:12345678903");
        TestPrilog stranka = TestPrilog.nevezan(2, Ucitao.lessor(UUID.randomUUID()));

        assertThat(PrilogService.pripadaPismenu(sluzbenik, true)).isTrue();
        assertThat(PrilogService.pripadaPismenu(sluzbenik, false)).isFalse();
        assertThat(PrilogService.pripadaPismenu(stranka, false)).isTrue();
        assertThat(PrilogService.pripadaPismenu(stranka, true)).isFalse();
    }

    // --- T17/B-2: ukupna veličina priloga službenika -----------------------------------------

    private static final String SLUZBENIK = "INTERNAL:12345678903";

    /** Limit testa je 150 B: 80 + 80 je preko, kod je ATTACHMENT_TOTAL_TOO_LARGE i ništa se ne veže. */
    @Test
    void bind_official_overTotalLimit_is400_andBindsNothing() {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeci(List.of(1L, 2L))).thenReturn(List.of(
                TestPrilog.nevezan(1, SLUZBENIK).withVelicina(80), TestPrilog.nevezan(2, SLUZBENIK).withVelicina(80)));

        assertCode(() -> service.bind(SLUZBENIK, List.of(1L, 2L), PrilogVlasnik.prijelaz(logId)),
                HttpStatus.BAD_REQUEST, PrilogException.TOTAL_TOO_LARGE);
        verify(repository, never()).bind(any(), any(), any(), any(), any());
    }

    @Test
    void bind_official_atTotalLimit_binds() {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeci(List.of(1L, 2L))).thenReturn(List.of(
                TestPrilog.nevezan(1, SLUZBENIK).withVelicina(75), TestPrilog.nevezan(2, SLUZBENIK).withVelicina(75)));
        when(repository.bind(any(), eq(SLUZBENIK), eq(VrstaVlasnika.RN_LOG), eq(logId), any())).thenReturn(2);

        service.bind(SLUZBENIK, List.of(1L, 2L), PrilogVlasnik.prijelaz(logId));

        verify(repository).bind(eq(List.of(1L, 2L)), eq(SLUZBENIK), eq(VrstaVlasnika.RN_LOG), eq(logId), any());
    }

    /** Već vezani prilozi istog prijelaza ulaze u zbroj. */
    @Test
    void bind_official_countsAlreadyBoundTowardsTotal() {
        UUID logId = UUID.randomUUID();
        when(repository.findSazeci(List.of(2L))).thenReturn(List.of(TestPrilog.nevezan(2, SLUZBENIK).withVelicina(80)));
        when(repository.countByVrstaVlasnikaAndVlasnikId(VrstaVlasnika.RN_LOG, logId)).thenReturn(1L);
        when(repository.sumVelicinaVlasnika(VrstaVlasnika.RN_LOG, logId)).thenReturn(80L);

        assertCode(() -> service.bind(SLUZBENIK, List.of(2L), PrilogVlasnik.prijelaz(logId)),
                HttpStatus.BAD_REQUEST, PrilogException.TOTAL_TOO_LARGE);
    }

    /** Limit vrijedi samo za službenika (dostava e-poštom); stranka ima samo limit po datoteci. */
    @Test
    void bind_party_hasNoTotalLimit() {
        UUID submissionId = UUID.randomUUID();
        when(repository.findSazeci(List.of(1L, 2L))).thenReturn(List.of(
                TestPrilog.nevezan(1, ANA).withVelicina(90), TestPrilog.nevezan(2, ANA).withVelicina(90)));
        when(repository.bind(any(), eq(ANA), eq(VrstaVlasnika.SUBMISSION), eq(submissionId), any())).thenReturn(2);

        service.bind(ANA, List.of(1L, 2L), PrilogVlasnik.zahtjev(submissionId));

        verify(repository).bind(any(), eq(ANA), eq(VrstaVlasnika.SUBMISSION), eq(submissionId), any());
    }

    // --- T17/B-2: dodatne datoteke prigovora (spremiVezane) ----------------------------------

    @Test
    void spremiVezane_storesEachFileBound_inOrder() {
        UUID objectionId = UUID.randomUUID();
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};

        List<PrilogResponse> spremljeni = service.spremiVezane(ANA, List.of(
                new MockMultipartFile("datoteke", "dokaz.pdf", null, PDF),
                new MockMultipartFile("datoteke", "foto.jpg", null, jpg)), PrilogVlasnik.prigovor(objectionId), 1);

        ArgumentCaptor<PismenoPrilogEntity> saved = ArgumentCaptor.forClass(PismenoPrilogEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(e -> {
            assertThat(e.isVezan()).isTrue();
            assertThat(e.getVrstaVlasnika()).isEqualTo(VrstaVlasnika.OBJECTION);
            assertThat(e.getVlasnikId()).isEqualTo(objectionId);
            assertThat(e.getUcitao()).isEqualTo(ANA);
            assertThat(e.getVezanoU()).isNotNull();
        });
        assertThat(spremljeni).extracting(PrilogResponse::naziv).containsExactly("dokaz.pdf", "foto.jpg");
        assertThat(spremljeni).extracting(PrilogResponse::contentType).containsExactly("application/pdf", "image/jpeg");
    }

    /** Dokument prigovora (vecPrilozeno = 1) + 6 dodatnih = 7 > 6 ({@code max-per-objection}). */
    @Test
    void spremiVezane_overCountWithFirstDocument_is400_andStoresNothing() {
        List<org.springframework.web.multipart.MultipartFile> pet = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> (org.springframework.web.multipart.MultipartFile)
                        new MockMultipartFile("datoteke", "d" + i + ".pdf", null, PDF))
                .toList();

        assertCode(() -> service.spremiVezane(ANA, pet, PrilogVlasnik.prigovor(UUID.randomUUID()), 1),
                HttpStatus.BAD_REQUEST, PrilogException.COUNT);
        verify(repository, never()).save(any());
    }

    /** Neispravna druga datoteka: ni prva se ne sprema (provjere prije pohrane). */
    @Test
    void spremiVezane_invalidFile_storesNothing() {
        assertCode(() -> service.spremiVezane(ANA, List.of(
                        new MockMultipartFile("datoteke", "dokaz.pdf", null, PDF),
                        new MockMultipartFile("datoteke", "lazni.pdf", null, "nije pdf".getBytes())),
                        PrilogVlasnik.prigovor(UUID.randomUUID()), 1),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, PrilogException.TYPE);
        verify(repository, never()).save(any());
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(PrilogException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }
}
