package com.str.backend.auth.nias;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
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
            String xml = new String(Base64.getDecoder().decode(base64SamlResponse), StandardCharsets.UTF_8);
            Document doc = parse(xml);
            NodeList responses = doc.getElementsByTagNameNS(SAML_PROTOCOL_NS, "Response");
            if (responses.getLength() == 0) {
                return null;
            }
            String value = ((Element) responses.item(0)).getAttribute("InResponseTo");
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // XXE: vanjski entiteti i DTD nemaju što raditi u SAML porukama.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new InputSource(new StringReader(xml)));
    }

    public static List<String> extractSessionIndexes(String samlResponse) {
        if (samlResponse == null || samlResponse.isBlank()) {
            return List.of();
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(samlResponse)));
            NodeList stmts = doc.getElementsByTagNameNS(SAML_ASSERTION_NS, "AuthnStatement");
            List<String> indexes = new ArrayList<>();
            for (int i = 0; i < stmts.getLength(); i++) {
                Element el = (Element) stmts.item(i);
                String idx = el.getAttribute("SessionIndex");
                if (idx != null && !idx.isBlank()) {
                    indexes.add(idx);
                }
            }
            return indexes;
        } catch (Exception e) {
            return List.of();
        }
    }
}
