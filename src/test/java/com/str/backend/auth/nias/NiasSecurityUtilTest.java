package com.str.backend.auth.nias;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trenuci iz SAML odgovora po kojima se prepoznaje prijava preuzeta iz NIAS SSO sjednice
 * ({@code AuthnInstant} izvorne prijave osjetno stariji od {@code IssueInstant} asercije). Čitaju se
 * samo iz asercije koju je Spring provjerio, ne s bilo kojeg mjesta u odgovoru.
 */
class NiasSecurityUtilTest {

    private static final String OPEN = "<saml2p:Response xmlns:saml2p=\"urn:oasis:names:tc:SAML:2.0:protocol\""
            + " xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"_r\" InResponseTo=\"ARQ-42\""
            + " Version=\"2.0\" IssueInstant=\"2026-10-14T11:11:11Z\">";
    private static final String CLOSE = "</saml2p:Response>";

    /** Odgovor s jednom asercijom; {@code issueInstant} je asercijin (odgovor ima drugi, namjerno). */
    static String response(String issueInstant, String authnStatements) {
        return OPEN + assertion("_a", issueInstant, authnStatements) + CLOSE;
    }

    static String assertion(String id, String issueInstant, String content) {
        return "<saml2:Assertion ID=\"" + id + "\" Version=\"2.0\""
                + (issueInstant == null ? "" : " IssueInstant=\"" + issueInstant + "\"") + ">"
                + (content == null ? "" : content)
                + "</saml2:Assertion>";
    }

    static String authnStatement(String authnInstant) {
        return authnStatement(authnInstant, "_s1");
    }

    static String authnStatement(String authnInstant, String sessionIndex) {
        return "<saml2:AuthnStatement AuthnInstant=\"" + authnInstant + "\" SessionIndex=\"" + sessionIndex + "\"/>";
    }

    @Test
    void readsAuthnAndIssueInstant() {
        String xml = response("2026-10-14T10:00:00.250Z", authnStatement("2026-10-14T09:40:00Z"));

        assertThat(NiasSecurityUtil.authnInstantOf(xml)).isEqualTo(Instant.parse("2026-10-14T09:40:00Z"));
        // IssueInstant asercije (potpisan), ne omotnice odgovora (11:11:11).
        assertThat(NiasSecurityUtil.issueInstantOf(xml)).isEqualTo(Instant.parse("2026-10-14T10:00:00.250Z"));
        assertThat(NiasSecurityUtil.authnAgeOf(xml)).isEqualTo(Duration.ofMinutes(20).plusMillis(250));
    }

    /** Uz više AuthnStatement odlučuje najnoviji — jedna svježa prijava znači svježu prijavu. */
    @Test
    void latestAuthnStatementWins() {
        String older = authnStatement("2026-10-14T08:00:00Z");
        String fresh = authnStatement("2026-10-14T09:59:58Z");

        assertThat(NiasSecurityUtil.authnAgeOf(response("2026-10-14T10:00:00Z", older + fresh)))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(NiasSecurityUtil.authnAgeOf(response("2026-10-14T10:00:00Z", fresh + older)))
                .isEqualTo(Duration.ofSeconds(2));
    }

    /** AuthnInstant poslije IssueInstant (nedosljedan IdP) vraća se kao negativna starost, ne kao greška. */
    @Test
    void authnAfterIssue_isNegativeAge() {
        assertThat(NiasSecurityUtil.authnAgeOf(response("2026-10-14T10:00:00Z", authnStatement("2026-10-14T10:00:05Z"))))
                .isEqualTo(Duration.ofSeconds(-5));
    }

    @Test
    void missingAuthnStatement_isNull() {
        String xml = response("2026-10-14T10:00:00Z", null);

        assertThat(NiasSecurityUtil.authnInstantOf(xml)).isNull();
        assertThat(NiasSecurityUtil.issueInstantOf(xml)).isNotNull();
        assertThat(NiasSecurityUtil.authnAgeOf(xml)).isNull();
    }

    /** Šifrirana asercija: u neraščlanjenom odgovoru je samo EncryptedAssertion — dosadašnje ponašanje. */
    @Test
    void encryptedAssertion_isNull() {
        String xml = OPEN + "<saml2:EncryptedAssertion><xenc:EncryptedData xmlns:xenc=\"http://www.w3.org/2001/04/xmlenc#\"/>"
                + "</saml2:EncryptedAssertion>" + CLOSE;

        assertThat(NiasSecurityUtil.authnAgeOf(xml)).isNull();
        assertThat(NiasSecurityUtil.issueInstantOf(xml)).isNull();
        assertThat(NiasSecurityUtil.extractSessionIndexes(xml)).isEmpty();
    }

    @Test
    void missingOrBlankAttribute_isNull() {
        assertThat(NiasSecurityUtil.issueInstantOf(response(null, authnStatement("2026-10-14T09:40:00Z")))).isNull();
        assertThat(NiasSecurityUtil.authnAgeOf(response(null, authnStatement("2026-10-14T09:40:00Z")))).isNull();
        assertThat(NiasSecurityUtil.authnInstantOf(response("2026-10-14T10:00:00Z", authnStatement(" ")))).isNull();
    }

    /** xs:dateTime: razlomci sekunde, pomak umjesto Z i vrijednost bez zone (po SAML-u UTC). */
    @Test
    void parsesXsdDateTimeVariants() {
        Instant ten = Instant.parse("2026-10-14T10:00:00Z");

        assertThat(NiasSecurityUtil.instantOf("2026-10-14T10:00:00Z")).isEqualTo(ten);
        assertThat(NiasSecurityUtil.instantOf("2026-10-14T10:00:00.123Z")).isEqualTo(ten.plusMillis(123));
        assertThat(NiasSecurityUtil.instantOf("2026-10-14T10:00:00.1234567Z")).isEqualTo(ten.plusNanos(123_456_700));
        assertThat(NiasSecurityUtil.instantOf("2026-10-14T12:00:00+02:00")).isEqualTo(ten);
        assertThat(NiasSecurityUtil.instantOf("2026-10-14T12:00:00.5+02:00")).isEqualTo(ten.plusMillis(500));
        assertThat(NiasSecurityUtil.instantOf("2026-10-14T10:00:00")).isEqualTo(ten);
        assertThat(NiasSecurityUtil.instantOf(" 2026-10-14T10:00:00Z ")).isEqualTo(ten);
    }

    @Test
    void unparsableInstant_isNull() {
        assertThat(NiasSecurityUtil.authnInstantOf(response("2026-10-14T10:00:00Z", authnStatement("jučer")))).isNull();
        assertThat(NiasSecurityUtil.issueInstantOf(response("14.10.2026. 10:00", null))).isNull();
        assertThat(NiasSecurityUtil.instantOf("2026-10-14")).isNull();
        assertThat(NiasSecurityUtil.instantOf("2026-13-14T10:00:00Z")).isNull();
    }

    @Test
    void malformedOrEmptyXml_isNull() {
        assertThat(NiasSecurityUtil.authnInstantOf("<saml2p:Response")).isNull();
        assertThat(NiasSecurityUtil.issueInstantOf("nije xml")).isNull();
        assertThat(NiasSecurityUtil.authnAgeOf(null)).isNull();
        assertThat(NiasSecurityUtil.authnAgeOf("  ")).isNull();
        assertThat(NiasSecurityUtil.extractSessionIndexes("nije xml")).isEmpty();
    }

    /** Korijen mora biti Response; asercija bez njega nije ono što je Spring provjerio. */
    @Test
    void rootOtherThanResponse_isNull() {
        String bare = "<saml2:Assertion xmlns:saml2=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"_a\""
                + " IssueInstant=\"2026-10-14T10:00:00Z\">" + authnStatement("2026-10-14T09:00:00Z") + "</saml2:Assertion>";

        assertThat(NiasSecurityUtil.authnAgeOf(bare)).isNull();
        assertThat(NiasSecurityUtil.extractSessionIndexes(bare)).isEmpty();
    }

    /**
     * Spring provjerava potpis izravne asercije odgovora i iz prve gradi principal. AuthnStatement
     * podmetnut drugdje (nepotpisan {@code Extensions}, druga asercija, {@code Advice}) se ne čita.
     */
    @Test
    void readsOnlyTheFirstDirectAssertion() {
        String injected = authnStatement("2020-01-01T00:00:00Z", "_podmetnut");
        String xml = OPEN
                + "<saml2p:Extensions>" + assertion("_x", "2026-10-14T10:00:00Z", injected) + injected + "</saml2p:Extensions>"
                + assertion("_a", "2026-10-14T10:00:00Z",
                        "<saml2:Advice>" + assertion("_adv", "2026-10-14T10:00:00Z", injected) + "</saml2:Advice>"
                                + authnStatement("2026-10-14T09:59:58Z"))
                + assertion("_b", "2026-10-14T10:00:00Z", injected)
                + CLOSE;

        assertThat(NiasSecurityUtil.authnAgeOf(xml)).isEqualTo(Duration.ofSeconds(2));
        assertThat(NiasSecurityUtil.extractSessionIndexes(xml)).containsExactly("_s1");
    }

    @Test
    void sessionIndexesOfAllAuthnStatements() {
        String xml = response("2026-10-14T10:00:00Z",
                authnStatement("2026-10-14T09:00:00Z", "_s1") + authnStatement("2026-10-14T09:30:00Z", "_s2")
                        + "<saml2:AuthnStatement AuthnInstant=\"2026-10-14T09:30:00Z\"/>");

        assertThat(NiasSecurityUtil.extractSessionIndexes(xml)).containsExactly("_s1", "_s2");
    }

    /** Isti zaštićeni parser za sve: DOCTYPE (XXE, „billion laughs") se odbija i kod SessionIndexa. */
    @Test
    void doctype_isRejected() {
        String xml = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + response("2026-10-14T10:00:00Z", authnStatement("2026-10-14T09:40:00Z"));
        String laughs = "<!DOCTYPE x [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>"
                + response("2026-10-14T10:00:00Z", authnStatement("2026-10-14T09:40:00Z", "&b;"));

        assertThat(NiasSecurityUtil.authnInstantOf(xml)).isNull();
        assertThat(NiasSecurityUtil.issueInstantOf(xml)).isNull();
        assertThat(NiasSecurityUtil.authnAgeOf(xml)).isNull();
        assertThat(NiasSecurityUtil.extractSessionIndexes(xml)).isEmpty();
        assertThat(NiasSecurityUtil.extractSessionIndexes(laughs)).isEmpty();
        assertThat(NiasSecurityUtil.inResponseToOf(xml)).isNull();
    }

    @Test
    void inResponseToStillRead() {
        assertThat(NiasSecurityUtil.inResponseToOf(response("2026-10-14T10:00:00Z", null))).isEqualTo("ARQ-42");
        assertThat(NiasSecurityUtil.inResponseToOf(OPEN.replace(" InResponseTo=\"ARQ-42\"", "") + CLOSE)).isNull();
    }
}
