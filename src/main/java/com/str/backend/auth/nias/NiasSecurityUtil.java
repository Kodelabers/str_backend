package com.str.backend.auth.nias;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public final class NiasSecurityUtil {

    private static final String SAML_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";

    private NiasSecurityUtil() {}

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
