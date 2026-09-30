package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Koji klijent okolina dobije i da se stvarni klijent složi iz NIAS keystorea i javnih
 * certifikata iz jara — kao na CDU-u, samo bez mreže.
 */
class EOvlastenjaConfigTest {

    private static final String TST_URL = "https://roapiservistst.fina.hr/api/AuthUnionApi/GetAuthorizationUnionPermission";
    private static final String CA_BUNDLE = "classpath:eovlastenja/ca-bundle.crt";
    private static final String SIGNER_TST = "classpath:eovlastenja/eovlastenja-tst.cer";
    private static final String PASSWORD = "changeit";

    private static final EOvlastenjaProperties.MockRepresentation PAIR = new EOvlastenjaProperties.MockRepresentation(
            "99999999990", "33333333360", "TESTNA TVRTKA d.o.o.", "Direktor");

    private final EOvlastenjaConfig config = new EOvlastenjaConfig();

    // ── enabled=false ───────────────────────────────────────────────────────

    /** Ugašena e-Ovlaštenja uz NIAS: 503, a ne „niste zastupnik" — to nitko nije provjerio. */
    @Test
    void disabled_withoutMockPairs_isUnavailable() {
        EOvlastenjaClient client = config.eOvlastenjaClientWhenDisabled(disabled(null), true);

        assertThatThrownBy(() -> client.verifyRepresentation("s", "99999999990", "33333333360"))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("nisu uključena");
        assertThatThrownBy(() -> client.representedCompanies("s", "99999999990"))
                .isInstanceOf(ExternalRegistryException.class);
    }

    @Test
    void mock_listsCompaniesOfThePerson() {
        EOvlastenjaClient client = config.eOvlastenjaClientWhenDisabled(disabled(List.of(PAIR)), false);

        assertThat(client.representedCompanies(null, "99999999990"))
                .containsExactly(new ZastupanaTvrtka("33333333360", "TESTNA TVRTKA d.o.o."));
        assertThat(client.representedCompanies(null, "70000000004")).isEmpty();
    }

    /** GetNavigationData je na istom AuthUnionApi — adresa se izvodi, test i prod. */
    @Test
    void navigationUrl_isDerivedFromPermissionUrl() {
        assertThat(enabled("/x.p12", null, CA_BUNDLE, SIGNER_TST).navigationUrl())
                .isEqualTo("https://roapiservistst.fina.hr/api/AuthUnionApi/GetNavigationData");
    }

    @Test
    void enabled_unexpectedUrl_abortsStartup() {
        EOvlastenjaProperties p = new EOvlastenjaProperties(true, "https://roapiservistst.fina.hr/api/nesto",
                "/x.p12", PASSWORD, null, null, null, SIGNER_TST, null, null, null);

        assertThatThrownBy(() -> config.eOvlastenjaHttpClient(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GetAuthorizationUnionPermission");
    }

    /** Mock uz NIAS dao bi stvarnom korisniku zastupanje bez provjere. */
    @Test
    void disabled_mockPairsWithNias_abortStartup() {
        assertThatThrownBy(() -> config.eOvlastenjaClientWhenDisabled(disabled(List.of(PAIR)), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nias.saml.enabled");
    }

    @Test
    void disabled_mockPairsWithoutNias_mockRuns() {
        EOvlastenjaClient client = config.eOvlastenjaClientWhenDisabled(disabled(List.of(PAIR)), false);

        assertThat(client.verifyRepresentation(null, "99999999990", "33333333360").legalName())
                .isEqualTo("TESTNA TVRTKA d.o.o.");
        assertThatThrownBy(() -> client.verifyRepresentation(null, "99999999990", "85821130368"))
                .isInstanceOfSatisfying(EOvlastenjaException.class,
                        e -> assertThat(e.reason()).isEqualTo(EOvlastenjaException.Reason.NOT_REPRESENTATIVE));
    }

    @Test
    void disabled_incompleteMockPair_abortsStartup() {
        EOvlastenjaProperties.MockRepresentation noName =
                new EOvlastenjaProperties.MockRepresentation("99999999990", "33333333360", " ", null);

        assertThatThrownBy(() -> config.eOvlastenjaClientWhenDisabled(disabled(List.of(noName)), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock[0].legal-name");
    }

    // ── enabled=true ────────────────────────────────────────────────────────

    /** CDU konfiguracija: NIAS keystore s diska, truststore i potpisni certifikat iz jara. */
    @Test
    void enabled_buildsClientFromNiasKeystoreAndJarCertificates(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("keystore.p12");
        keytool("-genkeypair", "-alias", "interniturizam (fina demo ca 2020)", "-dname", "CN=InterniTurizam Test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD);

        EOvlastenjaClient client = config.eOvlastenjaHttpClient(enabled(keystore.toString(),
                "interniturizam (fina demo ca 2020)", CA_BUNDLE, SIGNER_TST));

        assertThat(client).isInstanceOf(EOvlastenjaHttpClient.class);
    }

    @Test
    void enabled_wrongKeyAlias_abortsStartup(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("keystore.p12");
        keytool("-genkeypair", "-alias", "drugi", "-dname", "CN=Test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD);

        assertThatThrownBy(() -> config.eOvlastenjaHttpClient(enabled(keystore.toString(), "interniturizam", CA_BUNDLE, SIGNER_TST)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alias");
    }

    @Test
    void enabled_withoutSignerCertificate_abortsStartup() {
        assertThatThrownBy(() -> config.eOvlastenjaHttpClient(enabled("/secrets/keystore.p12", null, CA_BUNDLE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signer-cert-path");
    }

    // ── javni certifikati u jaru ────────────────────────────────────────────

    @Test
    void jarShipsValidSignerCertificates() throws Exception {
        X509Certificate tst = EOvlastenjaConfig.loadCertificate(SIGNER_TST);
        X509Certificate prod = EOvlastenjaConfig.loadCertificate("classpath:eovlastenja/eovlastenja-prod.cer");

        assertThat(tst.getSubjectX500Principal().getName()).contains("CN=Upravljanje-eOvlastenjimaTst");
        assertThat(prod.getSubjectX500Principal().getName()).contains("CN=eovlastenjaprod");
        tst.checkValidity(new Date());
        prod.checkValidity(new Date());
    }

    /** Serverski TLS roapiservis* ide do Sectigo R46, ne do Fina Demo CA (izmjereno 29.09.2026.). */
    @Test
    void caBundle_carriesSectigoRootAndFinaDemoCas() throws Exception {
        List<Certificate> cas = EOvlastenjaConfig.extraCaCertificates(enabled("/x.p12", null, CA_BUNDLE, SIGNER_TST));

        assertThat(cas).extracting(c -> ((X509Certificate) c).getSubjectX500Principal().getName())
                .anyMatch(n -> n.contains("CN=Sectigo Public Server Authentication Root R46"))
                .anyMatch(n -> n.contains("CN=Fina Demo CA 2020"))
                .anyMatch(n -> n.contains("CN=Fina Demo Root CA"));
    }

    // ── pomoćne ─────────────────────────────────────────────────────────────

    private static EOvlastenjaProperties disabled(List<EOvlastenjaProperties.MockRepresentation> mock) {
        return new EOvlastenjaProperties(false, null, null, null, null, null, null, null, null, null, mock);
    }

    private static EOvlastenjaProperties enabled(String keystore, String alias, String truststore, String signer) {
        return new EOvlastenjaProperties(true, TST_URL, keystore, PASSWORD, alias, truststore, null, signer,
                null, null, null);
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool nije uspio");
        }
    }
}
