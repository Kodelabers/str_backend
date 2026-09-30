package com.str.backend.registries.eovlastenja;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.ResourceUtils;
import org.springframework.web.client.RestClient;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stvarni klijent uz {@code app.eovlastenja.enabled=true}, inače mock ili isključen klijent.
 * Propertyji se registriraju bezuvjetno, jer ih mock čita za svoje parove.
 *
 * <p>Transport je {@link HttpsURLConnection} (blokirajući {@code SSLSocket}, uvijek HTTP/1.1):
 * {@code roapiservistst} nudi h2, a klijentski certifikat <b>ne</b> traži u prvom handshakeu
 * (provjereno 29.09.2026. — „No client certificate CA names sent"), nego naknadno,
 * renegotiationom. HTTP/2 renegotiation zabranjuje; JTI iz istog razloga ide klasičnim
 * HTTP/1.1 klijentom. Bez nove ovisnosti.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EOvlastenjaProperties.class)
public class EOvlastenjaConfig {

    private static final Logger log = LoggerFactory.getLogger(EOvlastenjaConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.eovlastenja.enabled", havingValue = "true")
    EOvlastenjaClient eOvlastenjaHttpClient(EOvlastenjaProperties properties) throws Exception {
        properties.requireComplete();
        X509Certificate signer = loadCertificate(properties.signerCertPath());
        log.info("eovlastenja url={} navigation_url={} signer={} signer_valid_until={}",
                properties.url(), properties.navigationUrl(), signer.getSubjectX500Principal().getName(),
                signer.getNotAfter());

        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory(sslContext(properties).getSocketFactory(), properties))
                .build();
        return new EOvlastenjaHttpClient(restClient, new EOvlastenjaResponseParser(signer),
                properties.url(), properties.navigationUrl());
    }

    /**
     * Bez stvarnog klijenta: mock s konfiguriranim parovima (local/mock), a bez parova klijent
     * koji svaku provjeru odbija kao nedostupnu (503). Bez toga bi okolina s ugašenim
     * e-Ovlaštenjima stvarnom korisniku javljala „niste zastupnik" — što nije utvrđeno.
     *
     * <p>Mock uz uključen NIAS se odbija na startu: stvarni NIAS korisnik s OIB-om iz para bi
     * djelovao u ime tvrtke bez ikakve provjere.
     */
    @Bean
    @ConditionalOnProperty(name = "app.eovlastenja.enabled", havingValue = "false", matchIfMissing = true)
    EOvlastenjaClient eOvlastenjaClientWhenDisabled(EOvlastenjaProperties properties,
                                                    @Value("${nias.saml.enabled:false}") boolean niasEnabled) {
        List<EOvlastenjaProperties.MockRepresentation> pairs = properties.mockOrEmpty();
        if (pairs.isEmpty()) {
            log.info("eovlastenja iskljucena (app.eovlastenja.enabled=false) — odabir pravne osobe vraća 503");
            return new EOvlastenjaClientDisabled();
        }
        if (niasEnabled) {
            throw new IllegalStateException("app.eovlastenja.mock je postavljen uz nias.saml.enabled=true — "
                    + "stvarni NIAS korisnici bi djelovali u ime tvrtke bez provjere. Ukloniti mock "
                    + "parove ili uključiti app.eovlastenja.enabled.");
        }
        return new EOvlastenjaClientMock(pairs);
    }

    private static SimpleClientHttpRequestFactory requestFactory(SSLSocketFactory sockets, EOvlastenjaProperties p) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                if (!(connection instanceof HttpsURLConnection https)) {
                    throw new IOException("e-Ovlaštenja se pozivaju isključivo preko HTTPS-a");
                }
                https.setSSLSocketFactory(sockets);
                https.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofMillis(p.effectiveConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(p.effectiveReadTimeoutMs()));
        return factory;
    }

    private static SSLContext sslContext(EOvlastenjaProperties p) throws Exception {
        char[] keyPassword = p.keystorePassword().toCharArray();
        KeyStore source = loadKeyStore(p.keystorePath(), keyPassword);
        // Izdvaja se samo ključ usluge: p12 može imati više unosa, a KeyManager bi inače mogao
        // ponuditi krivi certifikat pa bi e-Ovlaštenja javila „Pristup metodi nije dozvoljen".
        String alias = p.keyAlias() != null && !p.keyAlias().isBlank()
                ? p.keyAlias() : firstKeyAlias(source);
        Key key = source.getKey(alias, keyPassword);
        Certificate[] chain = source.getCertificateChain(alias);
        if (key == null || chain == null) {
            throw new IllegalStateException("e-Ovlaštenja keystore nema ključ za alias '" + alias + "'");
        }
        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        clientStore.load(null, null);
        clientStore.setKeyEntry("client", key, keyPassword, chain);
        // Koji se certifikat nudi u obostranom TLS-u — šifra 100 ili odbijen handshake znače da
        // ga e-Ovlaštenja ne poznaju, pa ovo mora biti vidljivo bez pristupa keystoreu.
        if (chain[0] instanceof X509Certificate client) {
            log.info("eovlastenja_mtls alias={} client_cert={} issuer={} valid_until={} chain_length={}",
                    alias, client.getSubjectX500Principal().getName(), client.getIssuerX500Principal().getName(),
                    client.getNotAfter(), chain.length);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, keyPassword);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustAnchors(p));

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * JDK {@code cacerts} uz dodatne CA certifikate, ako su zadani. Serverske certifikate
     * {@code roapiservistst.fina.hr} i {@code roapiservis.gov.hr} izdaje lanac do javnog
     * „Sectigo Public Server Authentication Root R46" (ne Fina Demo CA, kako pretpostavlja JTI);
     * dodatak ({@code classpath:eovlastenja/ca-bundle.crt}) nosi taj root za starije base imageove
     * i FINA Demo CA-ove za slučaj da se iz državne mreže vidi drugi certifikat.
     */
    private static KeyStore trustAnchors(EOvlastenjaProperties p) throws Exception {
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        int i = 0;
        for (TrustManager tm : defaults.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                for (X509Certificate ca : x509.getAcceptedIssuers()) {
                    anchors.setCertificateEntry("jdk-" + i++, ca);
                }
            }
        }
        int jdk = i;
        for (Certificate ca : extraCaCertificates(p)) {
            anchors.setCertificateEntry("extra-" + i++, ca);
        }
        log.info("eovlastenja_trust anchors={} (jdk={}, extra={} iz {})", i, jdk, i - jdk,
                p.truststorePath() == null || p.truststorePath().isBlank() ? "-" : p.truststorePath());
        return anchors;
    }

    /** PKCS12 ({@code .p12}/{@code .pfx}, s lozinkom) ili PEM/DER certifikati, jedan ili više. */
    static List<Certificate> extraCaCertificates(EOvlastenjaProperties p) throws Exception {
        if (p.truststorePath() == null || p.truststorePath().isBlank()) {
            return List.of();
        }
        if (p.truststoreIsKeyStore()) {
            KeyStore store = loadKeyStore(p.truststorePath(), p.truststorePassword().toCharArray());
            List<Certificate> certs = new ArrayList<>();
            for (String alias : Collections.list(store.aliases())) {
                if (store.getCertificate(alias) != null) {
                    certs.add(store.getCertificate(alias));
                }
            }
            return certs;
        }
        try (InputStream in = open(p.truststorePath())) {
            return List.copyOf(CertificateFactory.getInstance("X.509").generateCertificates(in));
        }
    }

    private static KeyStore loadKeyStore(String location, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = open(location)) {
            ks.load(in, password);
        }
        return ks;
    }

    private static String firstKeyAlias(KeyStore ks) throws Exception {
        for (String alias : Collections.list(ks.aliases())) {
            if (ks.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new IllegalStateException("e-Ovlaštenja keystore nema nijedan privatni ključ");
    }

    /** DER (.cer iz FINA zipa) ili PEM — {@link CertificateFactory} prima oba. */
    static X509Certificate loadCertificate(String location) throws Exception {
        try (InputStream in = open(location)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    /**
     * Putanja na disku (keystore s ključem, {@code /secrets/…}) ili {@code classpath:…} (javni
     * certifikati koji idu u jar, {@code eovlastenja/}).
     */
    private static InputStream open(String location) throws IOException {
        return ResourceUtils.getURL(location).openStream();
    }
}
