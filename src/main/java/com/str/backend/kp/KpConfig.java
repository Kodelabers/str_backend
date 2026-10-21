package com.str.backend.kp;

import com.str.backend.kp.client.KpClient;
import com.str.backend.kp.client.KpSoapClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.util.ResourceUtils;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Transport prema SZP-u: SOAP 1.1 preko HTTPS-a s obostranom autentikacijom (tehnička spec. 6.2).
 *
 * <p>Transport je {@link HttpsURLConnection}, kao kod e-Ovlaštenja: {@code cistest.apis-it.hr}
 * u prvom handshakeu <b>ne</b> traži klijentski certifikat („No client certificate CA names sent",
 * provjereno 21.10.2026.), nego naknadno, a to radi samo klasični HTTP/1.1 klijent. Poslužitelj se
 * predstavlja certifikatom Fina Demo CA 2020 (test) odnosno Fina RDC 2020 (prod) i šalje samo
 * list, pa truststore mora imati i posrednički CA ({@code str.kp.truststore-path}).
 *
 * <p>Diže se samo uz uključen KP <b>i</b> eGOP: bez urudžbe akt nema klasu ni urudžbeni broj i ne
 * šalje se, pa ni klijent ne treba.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"hr.infodom.str.integration.egop.enabled", "str.kp.enabled"}, havingValue = "true")
public class KpConfig {

    private static final Logger log = LoggerFactory.getLogger(KpConfig.class);

    @Bean
    KpSoapClient kpClient(KpProperties properties) throws Exception {
        properties.requireComplete();
        log.info("kp_endpoint url={} sender_id={} id_posiljatelja_fo={} id_posiljatelja_ps={} encoding={}"
                        + " pravni_ucinak={} razina={}",
                properties.url(), properties.senderId(), properties.idPosiljateljaFo(),
                properties.idPosiljateljaPs(), properties.dataEncoding(), properties.pravniUcinak(),
                properties.razinaSigurnosti());

        SSLSocketFactory sockets = sslContext(properties).getSocketFactory();
        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender() {
            @Override
            protected void prepareConnection(HttpURLConnection connection) throws IOException {
                super.prepareConnection(connection);
                if (!(connection instanceof HttpsURLConnection https)) {
                    throw new IOException("Korisnički pretinac se poziva isključivo preko HTTPS-a");
                }
                https.setSSLSocketFactory(sockets);
                https.setInstanceFollowRedirects(false);
            }
        };
        sender.setConnectionTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        sender.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.afterPropertiesSet(); // SOAP 1.1 — spec 6.3
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("hr.apis.kp.gsb");
        marshaller.afterPropertiesSet();
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setMessageSender(sender);
        template.setDefaultUri(properties.url());
        return new KpSoapClient(template, properties);
    }

    /**
     * Jedan {@code echo} nakon starta — da se kriv certifikat, truststore ili zatvoren port vide
     * odmah u logu, a ne tek na prvom aktu stranci. Pad ne ruši aplikaciju: otpreme čekaju u
     * retryju.
     */
    @EventListener(ApplicationReadyEvent.class)
    void echo(ApplicationReadyEvent event) {
        KpClient client = event.getApplicationContext().getBean(KpClient.class);
        try {
            log.info("kp_echo ok response='{}'", client.echo("eTurizam STR"));
        } catch (RuntimeException e) {
            log.error("kp_echo failed: {} — provjeriti mrežu prema SZP-u, klijentski certifikat i truststore",
                    e.getMessage());
        }
    }

    private static SSLContext sslContext(KpProperties p) throws Exception {
        char[] password = p.keystorePassword().toCharArray();
        KeyStore source = loadKeyStore(p.keystorePath(), password);
        // Samo jedan ključ: p12 može imati više unosa, a SZP prepoznaje institucije po certifikatu
        // prijavljenom u obrascu — drugi certifikat bi bio M001 („niste ovlašteni").
        String alias = p.keyAlias() != null && !p.keyAlias().isBlank() ? p.keyAlias() : firstKeyAlias(source);
        Key key = source.getKey(alias, password);
        Certificate[] chain = source.getCertificateChain(alias);
        if (key == null || chain == null) {
            throw new IllegalStateException("KP keystore nema ključ za alias '" + alias + "'");
        }
        KeyStore client = KeyStore.getInstance("PKCS12");
        client.load(null, null);
        client.setKeyEntry("client", key, password, chain);
        if (chain[0] instanceof X509Certificate cert) {
            log.info("kp_mtls alias={} client_cert={} issuer={} valid_until={}", alias,
                    cert.getSubjectX500Principal().getName(), cert.getIssuerX500Principal().getName(),
                    cert.getNotAfter());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(client, password);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustAnchors(p));

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    /** JDK {@code cacerts} uz FINA CA-ove iz {@code str.kp.truststore-path}. */
    private static KeyStore trustAnchors(KpProperties p) throws Exception {
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
        log.info("kp_trust anchors={} (jdk={}, extra={} iz {})", i, jdk, i - jdk,
                p.truststorePath() == null || p.truststorePath().isBlank() ? "-" : p.truststorePath());
        return anchors;
    }

    /** PKCS12 (s lozinkom) ili PEM/DER, jedan ili više certifikata. */
    static Collection<? extends Certificate> extraCaCertificates(KpProperties p) throws Exception {
        String path = p.truststorePath();
        if (path == null || path.isBlank()) {
            return List.of();
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".p12") || lower.endsWith(".pfx")) {
            char[] password = p.truststorePassword() == null ? new char[0] : p.truststorePassword().toCharArray();
            KeyStore store = loadKeyStore(path, password);
            return Collections.list(store.aliases()).stream()
                    .map(alias -> {
                        try {
                            return store.getCertificate(alias);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        try (InputStream in = ResourceUtils.getURL(path).openStream()) {
            return CertificateFactory.getInstance("X.509").generateCertificates(in);
        }
    }

    private static KeyStore loadKeyStore(String location, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = ResourceUtils.getURL(location).openStream()) {
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
        throw new IllegalStateException("KP keystore nema nijedan privatni ključ");
    }
}
