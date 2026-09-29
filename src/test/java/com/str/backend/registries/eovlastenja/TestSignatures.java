package com.str.backend.registries.eovlastenja;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Testni potpisni ključevi i odgovori u obliku <b>stvarnog</b> FINA primjera
 * ({@code SignedAuthorizationUnionPermissionResponseType.xml}), ne „pročišćenog" iz specifikacije.
 * Potpisuje se kao u JTI {@code RealEovlastenjaClientTest}. Dijele ih testovi parsera i klijenta.
 */
final class TestSignatures {

    /** Ključ i certifikat; {@link #TRUSTED} i {@link #ATTACKER} imaju isti Subject DN. */
    record SigningKey(PrivateKey key, X509Certificate cert) {}

    private static final String DN = "CN=Upravljanje-eOvlastenjimaTst Test, O=FINA, C=HR";
    private static final String PASSWORD = "changeit";

    /** Pinani certifikat parsera. */
    static final SigningKey TRUSTED = generate("trusted");
    /** Napadač koji se DN-om predstavlja kao e-Ovlaštenja, s vlastitim ključem. */
    static final SigningKey ATTACKER = generate("attacker");

    private TestSignatures() {}

    static String representation() {
        return """
                <Representation xmlns="http://eovlastenja.fina.hr/authunion/v2"><DataLegalFor>\
                <Functions xmlns="http://eovlastenja.fina.hr/representationitems/v2">\
                <Function><Code>034</Code><Name>Direktor</Name><Source>0</Source></Function>\
                <Function><Code>031</Code><Name>Predsjednik uprave</Name><Source>0</Source></Function>\
                </Functions></DataLegalFor></Representation>""";
    }

    static String errors(String code) {
        return """
                <Errors xmlns="http://eovlastenja.fina.hr/authunion/v2">\
                <Error xmlns="http://eovlastenja.fina.hr/authorizationbase/v2"><Code>%s</Code><Message>poruka</Message></Error>\
                </Errors>""".formatted(code);
    }

    /** Oblik stvarnog FINA odgovora; {@code Person} ima Id samo radi testa signature wrappinga. */
    static String response(String forRequestId, String personOib, String legalOib, String representation, String errors) {
        return """
                <SignedAuthorizationUnionPermissionResponse Id="_424ddd3a33674274" ForRequestId="%s" \
                xmlns="http://eovlastenja.fina.hr/RoAuthUnionApi/v2">\
                <Person Id="_person" xmlns="http://eovlastenja.fina.hr/authunion/v2">\
                <OIB xmlns="http://eovlastenja.fina.hr/authorizationbase/v2">%s</OIB>\
                <FirstName xmlns="http://eovlastenja.fina.hr/authorizationbase/v2">ANA</FirstName>\
                <LastName xmlns="http://eovlastenja.fina.hr/authorizationbase/v2">HORVAT</LastName></Person>\
                <LegalTo xmlns="http://eovlastenja.fina.hr/authunion/v2">\
                <Name xmlns="http://eovlastenja.fina.hr/authorizationbase/v2">TESTNA TVRTKA</Name>\
                <Jips xmlns="http://eovlastenja.fina.hr/authorizationbase/v2"><IPS>%s</IPS><IZVOR_REG>1</IZVOR_REG></Jips>\
                </LegalTo>%s%s</SignedAuthorizationUnionPermissionResponse>""".formatted(
                forRequestId, personOib, legalOib, representation, errors);
    }

    static String signed(String xml) throws Exception {
        return sign(xml, TRUSTED, null);
    }

    /**
     * Potpis u {@code root/Signatures/Signature}, kao kod FINA-e. {@code referencedElement}
     * {@code null} znači potpis nad rootom; inače nad tim unutarnjim elementom.
     */
    static String sign(String xml, SigningKey signer, String referencedElement) throws Exception {
        return sign(xml, signer, referencedElement, DigestMethod.SHA256, SignatureMethod.RSA_SHA256,
                List.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE));
    }

    static String sign(String xml, SigningKey signer, String referencedElement,
                       String digest, String signatureMethod, List<String> transforms) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();
        Element target = root;
        if (referencedElement != null) {
            target = (Element) root.getElementsByTagNameNS("*", referencedElement).item(0);
        }
        target.setIdAttribute("Id", true);

        Element signatures = doc.createElementNS(EOvlastenjaResponseParser.ROOT_NS, "Signatures");
        root.appendChild(signatures);

        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        List<Transform> refTransforms = new ArrayList<>();
        for (String t : transforms) {
            refTransforms.add(fac.newTransform(t, (TransformParameterSpec) null));
        }
        Reference ref = fac.newReference("#" + target.getAttribute("Id"),
                fac.newDigestMethod(digest, null), refTransforms, null, null);
        SignedInfo signedInfo = fac.newSignedInfo(
                fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                fac.newSignatureMethod(signatureMethod, null),
                List.of(ref));
        KeyInfoFactory kif = fac.getKeyInfoFactory();
        fac.newXMLSignature(signedInfo, kif.newKeyInfo(List.of(kif.newX509Data(List.of(signer.cert())))))
                .sign(new DOMSignContext(signer.key(), signatures));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(out));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static SigningKey generate(String alias) {
        try {
            Path dir = Files.createTempDirectory("eovlastenja-test");
            Path p12 = dir.resolve(alias + ".p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", alias, "-dname", DN,
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storetype", "PKCS12",
                    "-keystore", p12.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool nije uspio");
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(p12)) {
                ks.load(in, PASSWORD.toCharArray());
            }
            Files.delete(p12);
            Files.delete(dir);
            return new SigningKey((PrivateKey) ks.getKey(alias, PASSWORD.toCharArray()),
                    (X509Certificate) ks.getCertificate(alias));
        } catch (Exception e) {
            throw new IllegalStateException("testni potpisni ključ nije generiran", e);
        }
    }
}
