package com.str.backend.registries.eovlastenja;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testni servis e-Ovlaštenja na {@code roapiservistst.fina.hr} pokazuje certifikat {@code gov.hr}. Uz
 * {@code tls-server-name} klijent ga prihvaća — ali samo za host iz adrese i samo s tim imenom, a lanac
 * i dalje provjerava truststore.
 */
class TlsServerNameVerifierTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir Path dir;
    private HttpsServer server;
    private KeyStore serverKeys;

    // ── pravilo ─────────────────────────────────────────────────────────────

    @Test
    void acceptsConfiguredName_onlyForConfiguredHost() throws Exception {
        SSLSession session = sessionWith(certificateWithDns("gov.hr", "*.gov.hr"));
        TlsServerNameVerifier verifier = new TlsServerNameVerifier("roapiservistst.fina.hr", "gov.hr");

        assertThat(verifier.verify("roapiservistst.fina.hr", session)).isTrue();
        assertThat(verifier.verify("ROAPISERVISTST.FINA.HR", session)).isTrue();
        assertThat(verifier.verify("drugi.fina.hr", session)).isFalse();
    }

    @Test
    void rejectsCertificateWithoutConfiguredName() throws Exception {
        TlsServerNameVerifier verifier = new TlsServerNameVerifier("roapiservistst.fina.hr", "gov.hr");

        assertThat(verifier.verify("roapiservistst.fina.hr", sessionWith(certificateWithDns("example.com"))))
                .isFalse();
        // Wildcard u konfiguraciji nije wildcard: ime se uspoređuje doslovno.
        assertThat(new TlsServerNameVerifier("roapiservistst.fina.hr", "evil.gov.hr")
                .verify("roapiservistst.fina.hr", sessionWith(certificateWithDns("gov.hr", "*.gov.hr")))).isFalse();
    }

    @Test
    void rejectsWhenPeerIsUnverified() throws Exception {
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenThrow(new javax.net.ssl.SSLPeerUnverifiedException("nema"));

        assertThat(new TlsServerNameVerifier("roapiservistst.fina.hr", "gov.hr")
                .verify("roapiservistst.fina.hr", session)).isFalse();
    }

    // ── stvarni HTTPS: JDK zove verifier tek nakon provjere lanca ────────────

    @BeforeEach
    void startServer() throws Exception {
        Path keystore = dir.resolve("server.p12");
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=gov.hr", "-ext", "SAN=dns:gov.hr,dns:*.gov.hr",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit");
        serverKeys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystore.toFile())) {
            serverKeys.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverKeys, PASSWORD);
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void standardCheck_rejectsCertificateForAnotherName() throws Exception {
        HttpsURLConnection connection = connection(trusting(serverKeys));

        assertThatThrownBy(connection::getResponseCode).isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void configuredServerName_acceptsTrustedCertificate() throws Exception {
        HttpsURLConnection connection = connection(trusting(serverKeys));
        connection.setHostnameVerifier(new TlsServerNameVerifier("localhost", "gov.hr"));

        assertThat(connection.getResponseCode()).isEqualTo(204);
    }

    /** Ime se popušta, lanac ne: certifikat kojem truststore ne vjeruje pada i uz verifier. */
    @Test
    void configuredServerName_stillRejectsUntrustedChain() throws Exception {
        // JDK cacerts: samopotpisani certifikat poslužitelja u njemu nije.
        TrustManagerFactory jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jdk.init((KeyStore) null);
        SSLContext jdkTls = SSLContext.getInstance("TLS");
        jdkTls.init(null, jdk.getTrustManagers(), null);
        HttpsURLConnection connection = connection(jdkTls);
        connection.setHostnameVerifier(new TlsServerNameVerifier("localhost", "gov.hr"));

        assertThatThrownBy(connection::getResponseCode).isInstanceOf(SSLHandshakeException.class);
    }

    // ── pomoćne ─────────────────────────────────────────────────────────────

    private HttpsURLConnection connection(SSLContext clientTls) throws Exception {
        URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setSSLSocketFactory(clientTls.getSocketFactory());
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        return connection;
    }

    private static SSLContext trusting(KeyStore source) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", source.getCertificate("server"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static X509Certificate certificateWithDns(String... names) throws Exception {
        X509Certificate cert = mock(X509Certificate.class);
        List<List<?>> sans = new ArrayList<>();
        for (String name : names) {
            sans.add(List.of(2, name));
        }
        when(cert.getSubjectAlternativeNames()).thenReturn(sans);
        return cert;
    }

    private static SSLSession sessionWith(X509Certificate leaf) throws Exception {
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[]{leaf});
        return session;
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
