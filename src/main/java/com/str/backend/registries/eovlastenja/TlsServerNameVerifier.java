package com.str.backend.registries.eovlastenja;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Provjera imena poslužitelja kad e-Ovlaštenja na svojoj adresi pokazuju certifikat izdan za drugo
 * ime. Testni servis na {@code roapiservistst.fina.hr} ima certifikat {@code CN=gov.hr} (SAN
 * {@code gov.hr}, {@code *.gov.hr}), pa standardna provjera imena uvijek pada; FINA preporučuje
 * provjeru lanca „neovisno o domeni".
 *
 * <p>Namjerno uže od isključene provjere imena:
 * <ul>
 *   <li>lanac certifikata i dalje provjerava truststore ({@code EOvlastenjaConfig#sslContext}) —
 *       ovaj verifier se poziva tek nakon uspješnog handshakea;</li>
 *   <li>vrijedi samo za host iz konfigurirane adrese e-Ovlaštenja;</li>
 *   <li>certifikat mora nositi točno konfigurirano ime ({@code app.eovlastenja.tls-server-name}) kao
 *       DNS ime u SAN-u — ne bilo koji certifikat kojem truststore vjeruje.</li>
 * </ul>
 *
 * <p>JDK ga zove samo kad standardna provjera imena ne prođe; adresa čiji certifikat odgovara
 * imenu hosta (npr. {@code roapiservis.gov.hr}) prolazi kao i bez njega.
 */
final class TlsServerNameVerifier implements HostnameVerifier {

    /** DNS ime u {@code subjectAltName} (RFC 5280, GeneralName dNSName). */
    private static final int SAN_DNS = 2;

    private final String expectedHost;
    private final String serverName;

    TlsServerNameVerifier(String expectedHost, String serverName) {
        this.expectedHost = expectedHost.toLowerCase(Locale.ROOT);
        this.serverName = serverName.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean verify(String host, SSLSession session) {
        if (host == null || !expectedHost.equals(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        try {
            Certificate[] peer = session.getPeerCertificates();
            return peer.length > 0 && peer[0] instanceof X509Certificate leaf && hasDnsName(leaf, serverName);
        } catch (SSLPeerUnverifiedException | CertificateParsingException e) {
            return false;
        }
    }

    static boolean hasDnsName(X509Certificate certificate, String name) throws CertificateParsingException {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) {
            return false;
        }
        for (List<?> entry : names) {
            if (entry.size() >= 2 && Integer.valueOf(SAN_DNS).equals(entry.get(0))
                    && entry.get(1) instanceof String dns && dns.toLowerCase(Locale.ROOT).equals(name)) {
                return true;
            }
        }
        return false;
    }
}
