package com.str.backend.auth.nias;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class NiasSecurityUtil {

    private static final String SAML_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";

    private NiasSecurityUtil() {}

    private static final String SAML_PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";

    /**
     * {@code InResponseTo} iz base64-kodiranog {@code SAMLResponse} parametra (POST binding).
     *
     * <p>Njime {@link DatabaseSaml2AuthenticationRequestRepository} pronalazi spremljeni
     * AuthnRequest bez oslanjanja na sesijski cookie. Vraća {@code null} kad atributa nema
     * (IdP-initiated odgovor) ili se ulaz ne da raščlaniti — pozivatelj to tretira kao
     * „nema spremljenog zahtjeva".
     */
    public static String extractInResponseTo(String base64SamlResponse) {
        if (base64SamlResponse == null || base64SamlResponse.isBlank()) {
            return null;
        }
        try {
            return inResponseToOf(new String(Base64.getDecoder().decode(base64SamlResponse), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code InResponseTo} iz već dekodiranog SAML odgovora ({@link
     * org.springframework.security.saml2.provider.service.authentication.Saml2Authentication#getSaml2Response()}).
     * Navigacijska traka ga traži kao {@code messageId} — v. {@link NavigationBarService}.
     */
    public static String inResponseToOf(String samlResponseXml) {
        if (samlResponseXml == null || samlResponseXml.isBlank()) {
            return null;
        }
        try {
            NodeList responses = parse(samlResponseXml).getElementsByTagNameNS(SAML_PROTOCOL_NS, "Response");
            if (responses.getLength() == 0) {
                return null;
            }
            return attribute((Element) responses.item(0), "InResponseTo");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code SessionIndex} iz {@code AuthnStatement} potpisane asercije (v. {@code signedAssertion}) — po
     * njima {@link NiasSessionRegistry} pri SLO-u nalazi sesiju. Prazno kad asercije nema (npr.
     * šifrirana) ili se odgovor ne da raščlaniti.
     */
    public static List<String> extractSessionIndexes(String samlResponseXml) {
        Element assertion = signedAssertion(samlResponseXml);
        if (assertion == null) {
            return List.of();
        }
        List<String> indexes = new ArrayList<>();
        for (Element statement : children(assertion, SAML_ASSERTION_NS, "AuthnStatement")) {
            String index = attribute(statement, "SessionIndex");
            if (index != null) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    /**
     * {@code AuthnInstant} iz potpisane asercije — trenutak kad je osoba stvarno unijela vjerodajnice.
     * Kad IdP odgovara iz postojeće SSO sjednice, to je vrijeme IZVORNE prijave (SAML 2.0 Core, 2.7.2).
     * Uz više {@code AuthnStatement} uzima se najnoviji: dovoljna je jedna svježa prijava da prijava
     * bude svježa. {@code null} kad ga nema (npr. šifrirana asercija) ili se ne da raščlaniti.
     */
    public static Instant authnInstantOf(String samlResponseXml) {
        Element assertion = signedAssertion(samlResponseXml);
        return assertion == null ? null : authnInstant(assertion);
    }

    /**
     * {@code IssueInstant} potpisane asercije — kad je IdP izdao odgovor. Ne čita se onaj iz
     * {@code Response}: kad NIAS potpisuje samo aserciju, omotnicu odgovora može mijenjati svatko
     * kroz čiji preglednik odgovor prolazi. {@code null} kao gore.
     */
    public static Instant issueInstantOf(String samlResponseXml) {
        Element assertion = signedAssertion(samlResponseXml);
        return assertion == null ? null : instantOf(attribute(assertion, "IssueInstant"));
    }

    /**
     * Koliko je prije izdavanja odgovora osoba unijela vjerodajnice ({@code IssueInstant − AuthnInstant},
     * oba iz iste potpisane asercije). Kod svježe prijave tek koja sekunda; veliko kad je NIAS odgovorio
     * iz postojeće SSO sjednice (npr. prijava u tuStart pa prelazak k nama). Oba trenutka su s NIAS-ova
     * sata, pa razlika između njegova i našeg sata ne utječe; negativna vrijednost (nedosljedan IdP)
     * vraća se kakva jest. {@code null} kad jedan od trenutaka nedostaje.
     */
    public static Duration authnAgeOf(String samlResponseXml) {
        Element assertion = signedAssertion(samlResponseXml);
        if (assertion == null) {
            return null;
        }
        Instant authn = authnInstant(assertion);
        Instant issued = instantOf(attribute(assertion, "IssueInstant"));
        return authn == null || issued == null ? null : Duration.between(authn, issued);
    }

    /**
     * Asercija iz koje je Spring složio prijavu: prvo izravno dijete korijenskog {@code <Response>}.
     * {@code OpenSaml4AuthenticationProvider} provjerava potpis svake takve asercije (ili cijelog
     * odgovora) i principal gradi iz prve. Pretraga po cijelom dokumentu nije isto: element podmetnut
     * u nepotpisan dio odgovora (npr. {@code <Extensions>}) ne smeta provjeri potpisa, a bio bi prvi po redu.
     */
    private static Element signedAssertion(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            Element root = parse(xml).getDocumentElement();
            if (!SAML_PROTOCOL_NS.equals(root.getNamespaceURI()) || !"Response".equals(root.getLocalName())) {
                return null;
            }
            List<Element> assertions = children(root, SAML_ASSERTION_NS, "Assertion");
            return assertions.isEmpty() ? null : assertions.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant authnInstant(Element assertion) {
        Instant latest = null;
        for (Element statement : children(assertion, SAML_ASSERTION_NS, "AuthnStatement")) {
            Instant instant = instantOf(attribute(statement, "AuthnInstant"));
            if (instant != null && (latest == null || instant.isAfter(latest))) {
                latest = instant;
            }
        }
        return latest;
    }

    /** Izravna djeca s danim imenom, redom kao u dokumentu — isto što vidi OpenSAML-ov model. */
    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && namespace.equals(el.getNamespaceURI()) && localName.equals(el.getLocalName())) {
                found.add(el);
            }
        }
        return found;
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * xs:dateTime. SAML traži UTC ({@code ...Z}), ali prihvaćamo i pomak ({@code +02:00}), razlomke
     * sekunde i vrijednost bez zone (po SAML-u je to UTC). Sve ostalo daje {@code null}.
     */
    static Instant instantOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parse(value.trim());
            return parsed.isSupported(ChronoField.INSTANT_SECONDS)
                    ? Instant.from(parsed)
                    : LocalDateTime.from(parsed).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Jedini parser SAML poruka u ovoj klasi. */
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // XXE i „billion laughs": vanjski entiteti i DTD nemaju što raditi u SAML porukama.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        // Inače parser svaki neispravan ulaz (i tuđi POST na ACS) ispiše na stderr kao [Fatal Error].
        db.setErrorHandler(new DefaultHandler());
        return db.parse(new InputSource(new StringReader(xml)));
    }
}
