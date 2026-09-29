package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaException.Reason;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.str.backend.registries.eovlastenja.TestSignatures.ATTACKER;
import static com.str.backend.registries.eovlastenja.TestSignatures.TRUSTED;
import static com.str.backend.registries.eovlastenja.TestSignatures.errors;
import static com.str.backend.registries.eovlastenja.TestSignatures.representation;
import static com.str.backend.registries.eovlastenja.TestSignatures.response;
import static com.str.backend.registries.eovlastenja.TestSignatures.sign;
import static com.str.backend.registries.eovlastenja.TestSignatures.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provjera odgovora e-Ovlaštenja. Odgovori se potpisuju testnim ključem, a struktura prati
 * <b>stvarni</b> FINA primjer ({@link TestSignatures}).
 */
class EOvlastenjaResponseParserTest {

    private static final String REQUEST_ID = "_3611996a1d404855a51229aab176f053";
    private static final String PERSON_OIB = "70000000004";
    private static final String LEGAL_OIB = "33333333360";

    private static final EOvlastenjaResponseParser parser = new EOvlastenjaResponseParser(TRUSTED.cert());

    // ── ispravan odgovor ────────────────────────────────────────────────────

    @Test
    void acceptsSignedRepresentation() throws Exception {
        Zastupanje z = parse(signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), "")));

        assertThat(z.personOib()).isEqualTo(PERSON_OIB);
        assertThat(z.personFirstName()).isEqualTo("ANA");
        assertThat(z.legalOib()).isEqualTo(LEGAL_OIB);
        assertThat(z.legalName()).isEqualTo("TESTNA TVRTKA");
        assertThat(z.functions()).extracting(Zastupanje.Funkcija::name).containsExactly("Direktor", "Predsjednik uprave");
        assertThat(z.functions().getFirst().code()).isEqualTo("034");
    }

    /** Oblik stvarnog FINA potpisa: SHA-1 digest uz RSA-SHA256 (secure validation ga odbija). */
    @Test
    void acceptsFinaSignatureShape_sha1Digest() throws Exception {
        String xml = sign(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""), TRUSTED,
                null, DigestMethod.SHA1, SignatureMethod.RSA_SHA256, List.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE));

        assertThat(parse(xml).functions()).hasSize(2);
    }

    /** Hrvatski znakovi u potpisanom sadržaju: bajtovi se ne smiju usput prekodirati. */
    @Test
    void acceptsNonAsciiContent() throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), "")
                .replace("TESTNA TVRTKA", "ĐURĐEVIĆ ČŠŽ d.o.o."));

        assertThat(parse(xml).legalName()).isEqualTo("ĐURĐEVIĆ ČŠŽ d.o.o.");
    }

    /** 403/404 (e-Punomoći isključene, preskočeni zapisi) ne tiču se e-Zastupanja. */
    @Test
    void informationalErrors_doNotBlockRepresentation() throws Exception {
        Zastupanje z = parse(signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), errors("403"))));

        assertThat(z.functions()).hasSize(2);
    }

    // ── potpis ──────────────────────────────────────────────────────────────

    @Test
    void rejectsSameDnWithDifferentKey() throws Exception {
        String xml = sign(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""), ATTACKER, null);

        // U poruci je certifikat koji je stvarno stigao — tako se vidi i da je FINA zamijenila svoj.
        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("KeyInfo: [CN=")
                .hasMessageContaining("serial " + ATTACKER.cert().getSerialNumber().toString(16));
    }

    @Test
    void rejectsUnsigned() {
        assertThatThrownBy(() -> parse(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), "")))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("nije potpisan");
    }

    /** Šifra iz nepotpisanog odgovora ide samo u poruku za dijagnostiku; odgovor se svejedno odbija. */
    @Test
    void rejectsUnsigned_butReportsItsErrorCodes() {
        assertThatThrownBy(() -> parse(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, "", errors("100"))))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("nije potpisan")
                .hasMessageContaining("[100]");
    }

    @Test
    void rejectsNonXml_withPreviewOfBody() {
        assertThatThrownBy(() -> parse("<html><body>502 Bad Gateway</body></html>\n<"))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("502 Bad Gateway");
    }

    /** Oblik odgovora za log: nazivi elemenata bez ijedne vrijednosti (OIB, ime, naziv). */
    @Test
    void shape_listsElementNamesOnly() throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""));
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Element root = dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();

        String shape = EOvlastenjaResponseParser.shape(root);

        assertThat(shape).startsWith("SignedAuthorizationUnionPermissionResponse[Person[OIB,FirstName,LastName]")
                .contains("Function[Code,Name,Source]×2")
                .contains("Signatures[…]")
                .doesNotContain(PERSON_OIB).doesNotContain(LEGAL_OIB).doesNotContain("ANA").doesNotContain("TESTNA");
    }

    @Test
    void rejectsTamperingAfterSigning() throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""))
                .replace("Predsjednik uprave", "Prokurist");

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class);
    }

    /**
     * XML Signature Wrapping: valjan potpis nad unutarnjim elementom ne smije potvrditi root.
     * Da parser provjerava samo „potpis je valjan", ovaj bi odgovor prošao. Odbija se već pri
     * razrješavanju reference — kao ID je registriran isključivo root — a provjera da je jedina
     * {@code Reference} {@code #<root Id>} je drugi sloj.
     */
    @Test
    void rejectsSignatureThatDoesNotCoverRoot() throws Exception {
        String xml = sign(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""), TRUSTED, "Person");

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class);
    }

    /** Popis algoritama zamjenjuje isključeni secure validation — i vrijedi prije izvršavanja transformacija. */
    @Test
    void rejectsSignatureAlgorithmOutsideAllowList() throws Exception {
        String xml = sign(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""), TRUSTED,
                null, DigestMethod.SHA256, SignatureMethod.RSA_SHA1, List.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE));

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class).hasMessageContaining("algoritam potpisa");
    }

    @Test
    void rejectsTransformOutsideAllowList() throws Exception {
        String xml = sign(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""), TRUSTED,
                null, DigestMethod.SHA256, SignatureMethod.RSA_SHA256,
                List.of(Transform.ENVELOPED, CanonicalizationMethod.INCLUSIVE_WITH_COMMENTS));

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class).hasMessageContaining("transformacija");
    }

    @Test
    void rejectsTooManyTransforms() throws Exception {
        String xml = sign(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), ""), TRUSTED,
                null, DigestMethod.SHA256, SignatureMethod.RSA_SHA256,
                List.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE, CanonicalizationMethod.EXCLUSIVE));

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class).hasMessageContaining("previše");
    }

    @Test
    void rejectsDoctype() {
        String xml = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, "&e;", "");

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class);
    }

    // ── sadržaj ─────────────────────────────────────────────────────────────

    @Test
    void rejectsResponseForAnotherRequest() throws Exception {
        String xml = signed(response("_drugizahtjev", PERSON_OIB, LEGAL_OIB, representation(), ""));

        assertThatThrownBy(() -> parse(xml)).hasMessageContaining("ForRequestId=_drugizahtjev");
    }

    @Test
    void rejectsResponseForAnotherPerson() throws Exception {
        String xml = signed(response(REQUEST_ID, "55555555551", LEGAL_OIB, representation(), ""));

        assertThatThrownBy(() -> parse(xml)).hasMessageContaining("osoba");
    }

    @Test
    void rejectsResponseForAnotherCompany() throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, "85821130368", representation(), ""));

        assertThatThrownBy(() -> parse(xml)).hasMessageContaining("subjekt");
    }

    /** Naziv tvrtke je iznajmljivač na PDF-u i aktima — bez njega bi se ispisalo ime zastupnika uz OIB tvrtke. */
    @Test
    void rejectsSubjectWithoutName() throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, representation(), "")
                .replace("<Name xmlns=\"http://eovlastenja.fina.hr/authorizationbase/v2\">TESTNA TVRTKA</Name>", ""));

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class).hasMessageContaining("naziv");
    }

    @Test
    void noRepresentation_isNotRepresentative() throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, "", ""));

        assertThatThrownBy(() -> parse(xml))
                .isInstanceOfSatisfying(EOvlastenjaException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_REPRESENTATIVE));
    }

    @Test
    void mapsErrorCodes() throws Exception {
        assertReason("200", Reason.SESSION);
        assertReason("203", Reason.SESSION);
        assertReason("400", Reason.NOT_REPRESENTATIVE);
        assertReason("401", Reason.NOT_REPRESENTATIVE);
        assertReason("500", Reason.SUBJECT_NOT_FOUND);
    }

    /** 100 = pristup metodi nije dozvoljen: to je kvar registracije, ne korisnikova greška. */
    @Test
    void accessNotAllowed_isRegistryFailure() throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, "", errors("100")));

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(ExternalRegistryException.class).hasMessageContaining("100");
    }

    // ── pomoćne ─────────────────────────────────────────────────────────────

    private void assertReason(String code, Reason reason) throws Exception {
        String xml = signed(response(REQUEST_ID, PERSON_OIB, LEGAL_OIB, "", errors(code)));
        assertThatThrownBy(() -> parse(xml))
                .isInstanceOfSatisfying(EOvlastenjaException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.code()).isEqualTo(code);
                });
    }

    private static Zastupanje parse(String xml) {
        return parser.parse(xml.getBytes(StandardCharsets.UTF_8), REQUEST_ID, PERSON_OIB, LEGAL_OIB);
    }
}
