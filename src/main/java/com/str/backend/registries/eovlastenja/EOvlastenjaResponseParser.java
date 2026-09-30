package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provjera i čitanje {@code SignedAuthorizationUnionPermissionResponse}.
 *
 * <p>Redoslijed je namjeran — ništa se ne čita prije nego što je dokazano da je potpisano:
 * <ol>
 *   <li>parsiranje bez DOCTYPE-a i vanjskih entiteta (XXE);</li>
 *   <li>root mora biti očekivani element, s atributom {@code Id};</li>
 *   <li>potpis mora pokrivati <b>root</b> — jedina {@code Reference} je {@code #<root Id>}. Bez
 *       toga je moguć XML Signature Wrapping: valjano potpisan drugi element, a podaci podmetnuti
 *       u root. Iz istog se razloga podaci čitaju samo kao djeca roota;</li>
 *   <li>algoritmi i transformacije moraju biti s popisa ({@link #requireExpectedShape}) — provjerava
 *       se <b>prije</b> {@code validate()}, jer ono izvršava transformacije iz dokumenta;</li>
 *   <li>XML-DSig potpis mora vrijediti <b>i</b> biti napravljen točno pinanim certifikatom
 *       e-Ovlaštenja (ključ iz {@code KeyInfo} se nikad ne uzima kao takav);</li>
 *   <li>{@code ForRequestId} mora biti {@code Id} našeg zahtjeva;</li>
 *   <li>osoba i subjekt iz odgovora moraju biti oni iz zahtjeva, a subjekt mora imati naziv.</li>
 * </ol>
 *
 * <p>Oblik je čitan tolerantno, po lokalnim imenima: stvarni FINA primjer razlikuje se od
 * „pročišćenog" u specifikaciji ({@code DataLegalFor} umjesto {@code DataEntityFor/DataLegal},
 * bez {@code EntityFor}). To je sigurno jer je cijeli root potpisan.
 */
final class EOvlastenjaResponseParser {

    static final String REGISTRY = "EOVLASTENJA";
    static final String ROOT_NS = "http://eovlastenja.fina.hr/RoAuthUnionApi/v2";
    static final String ROOT_NAME = "SignedAuthorizationUnionPermissionResponse";
    static final String NAVIGATION_ROOT_NAME = "NavigationDataResponse";

    private static final Logger log = LoggerFactory.getLogger(EOvlastenjaResponseParser.class);
    private static final Pattern OIB = Pattern.compile("\\d{11}");

    // Dopušteni oblik potpisa. FINA (primjer poruke): exc-c14n, rsa-sha256, enveloped + exc-c14n,
    // SHA-1 digest. Secure validation je isključen zbog SHA-1, pa ovaj popis preuzima njegovu
    // ulogu: bez XSLT/XPath transformacija, HMAC-a i slabih algoritama.
    private static final Set<String> CANONICALIZATIONS = Set.of(
            CanonicalizationMethod.EXCLUSIVE, CanonicalizationMethod.INCLUSIVE);
    private static final Set<String> SIGNATURE_METHODS = Set.of(
            SignatureMethod.RSA_SHA256, SignatureMethod.RSA_SHA384, SignatureMethod.RSA_SHA512);
    private static final Set<String> DIGESTS = Set.of(DigestMethod.SHA1, DigestMethod.SHA256, DigestMethod.SHA512);
    private static final Set<String> TRANSFORMS = Set.of(
            Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE, CanonicalizationMethod.INCLUSIVE);
    private static final int MAX_TRANSFORMS = 2;

    private final X509Certificate signerCert;

    EOvlastenjaResponseParser(X509Certificate signerCert) {
        this.signerCert = signerCert;
    }

    /** @param xml tijelo odgovora kako je stiglo; kodiranje određuje XML deklaracija, ne HTTP zaglavlje */
    Zastupanje parse(byte[] xml, String expectedRequestId, String expectedPersonOib, String expectedLegalOib) {
        Element root = verifiedRoot(xml);
        try {
            Zastupanje z = read(root, expectedRequestId, expectedPersonOib, expectedLegalOib);
            if (log.isDebugEnabled()) {
                log.debug("eovlastenja_response_shape {}", shape(root));
            }
            return z;
        } catch (EOvlastenjaException e) {
            if (log.isDebugEnabled()) {
                log.debug("eovlastenja_response_shape {}", shape(root));
            }
            throw e;
        } catch (ExternalRegistryException e) {
            // Potpis je valjan, a sadržaj nije ono što očekujemo. Oblik (samo nazivi elemenata, bez
            // vrijednosti) jedini pokazuje razliku prema FINA primjeru bez ispisa osobnih podataka.
            log.warn("eovlastenja_response_shape {}", shape(root));
            throw e;
        }
    }

    private static Zastupanje read(Element root, String expectedRequestId, String expectedPersonOib,
                                   String expectedLegalOib) {
        requireForRequestId(root, expectedRequestId);
        failOnErrors(root);
        Element person = requirePerson(root, expectedPersonOib);
        String personOib = text(person, "OIB");

        String legalName = verifySubject(root, expectedLegalOib);

        List<Zastupanje.Funkcija> functions = functions(child(root, "Representation"));
        if (functions.isEmpty()) {
            throw new EOvlastenjaException(Reason.NOT_REPRESENTATIVE, null,
                    "e-Ovlaštenja ne potvrđuju zastupanje za traženi subjekt");
        }
        return new Zastupanje(personOib, text(person, "FirstName"), text(person, "LastName"),
                expectedLegalOib, legalName, functions);
    }

    // ── popis tvrtki (GetNavigationData) ─────────────────────────────────────

    /**
     * {@code NavigationDataResponse}. Shema ga ne potpisuje (nema {@code Signatures}), pa se čita
     * samo za prikaz: sigurno parsiranje, {@code ForRequestId}, osoba iz zahtjeva i greške. U popis
     * idu samo tvrtke ({@code IZVOR_REG=1}) na temelju e-Zastupanja i to unutar same tvrtke — isti
     * par koji potvrđuje {@link #parse} ({@code JipsTo = IdentfiersFor}). e-Punomoći i djelovanje
     * kao djelatnik druge tvrtke ne ulaze.
     */
    List<ZastupanaTvrtka> parseNavigation(byte[] xml, String expectedRequestId, String expectedPersonOib) {
        Element root = parseDocument(xml).getDocumentElement();
        requireRoot(root, NAVIGATION_ROOT_NAME);
        try {
            List<ZastupanaTvrtka> companies = readNavigation(root, expectedRequestId, expectedPersonOib);
            if (log.isDebugEnabled()) {
                log.debug("eovlastenja_navigation_shape {}", shape(root));
            }
            return companies;
        } catch (EOvlastenjaException e) {
            if (log.isDebugEnabled()) {
                log.debug("eovlastenja_navigation_shape {}", shape(root));
            }
            throw e;
        } catch (ExternalRegistryException e) {
            log.warn("eovlastenja_navigation_shape {}", shape(root));
            throw e;
        }
    }

    private static List<ZastupanaTvrtka> readNavigation(Element root, String expectedRequestId,
                                                        String expectedPersonOib) {
        requireForRequestId(root, expectedRequestId);
        failOnErrors(root);
        requirePerson(root, expectedPersonOib);

        Map<String, ZastupanaTvrtka> companies = new LinkedHashMap<>();
        Element authorizations = child(root, "Authorizations");
        if (authorizations == null) {
            return List.of();
        }
        for (Element item : children(authorizations, "AuthorizationItem")) {
            // Bez LegalPersonTo osoba djeluje kao građanin; s njim — unutar te tvrtke.
            Element within = child(item, "LegalPersonTo");
            Element permissions = child(item, "PermissionsFor");
            if (permissions == null) {
                continue;
            }
            for (Element permission : children(permissions, "PermissionFor")) {
                Element entityFor = child(permission, "EntityFor");
                Element legal = entityFor != null ? child(entityFor, "Legal") : null;
                String ips = companyIps(legal);
                if (ips == null || !"true".equalsIgnoreCase(text(permission, "BasedOnRepresentation"))) {
                    continue;
                }
                if (within != null && !ips.equals(companyIps(within))) {
                    continue;
                }
                companies.putIfAbsent(ips, new ZastupanaTvrtka(ips, text(legal, "Name")));
            }
        }
        if (companies.isEmpty() && !children(authorizations, "AuthorizationItem").isEmpty()) {
            // Stavke postoje, a nijedna nije e-Zastupanje tvrtke — ili je stvarni oblik drugačiji od
            // FINA primjera. Oblik (bez vrijednosti) razlikuje ta dva slučaja.
            log.info("eovlastenja_navigation_no_companies shape={}", shape(root));
        }
        return List.copyOf(companies.values());
    }

    /** OIB tvrtke iz {@code Legal}/{@code LegalPersonTo}; {@code null} kad to nije tvrtka ({@code IZVOR_REG} ≠ 1). */
    private static String companyIps(Element legal) {
        Element jips = legal != null ? child(legal, "Jips") : null;
        if (jips == null || !"1".equals(text(jips, "IZVOR_REG"))) {
            return null;
        }
        String ips = text(jips, "IPS");
        return ips != null && OIB.matcher(ips).matches() ? ips : null;
    }

    // ── zajedničke provjere ──────────────────────────────────────────────────

    private static void requireRoot(Element root, String expectedName) {
        if (!ROOT_NS.equals(root.getNamespaceURI()) || !expectedName.equals(root.getLocalName())) {
            throw failure("neočekivan root element " + root.getLocalName());
        }
    }

    private static void requireForRequestId(Element root, String expectedRequestId) {
        String forRequestId = root.getAttribute("ForRequestId");
        if (!expectedRequestId.equals(forRequestId)) {
            // Id je naš, nasumičan i bez osobnih podataka — primljena vrijednost smije u log.
            throw failure("odgovor se ne odnosi na naš zahtjev (ForRequestId="
                    + (forRequestId.isEmpty() ? "prazan" : forRequestId) + ", očekivan " + expectedRequestId + ")");
        }
    }

    private static Element requirePerson(Element root, String expectedPersonOib) {
        Element person = child(root, "Person");
        String personOib = person != null ? text(person, "OIB") : null;
        if (!expectedPersonOib.equals(personOib)) {
            throw failure("osoba u odgovoru nije osoba iz zahtjeva");
        }
        return person;
    }

    /** XML bez DOCTYPE-a i vanjskih entiteta (XXE); kodiranje iz same poruke. */
    private static Document parseDocument(byte[] xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            // Bez ovoga Xerces grešku ispisuje i na stderr ("[Fatal Error] ..."), mimo loga.
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw failure("odgovor nije ispravan XML (" + xml.length + " B, početak: " + preview(xml) + ")", e);
        }
    }

    // ── potpis ───────────────────────────────────────────────────────────────

    private Element verifiedRoot(byte[] xml) {
        Element root = parseDocument(xml).getDocumentElement();
        requireRoot(root, ROOT_NAME);
        String rootId = root.getAttribute("Id");
        if (rootId.isEmpty()) {
            throw failure("root nema atribut Id");
        }
        // Referenca #Id se razrješava samo na atribute označene kao ID. Označava se ISKLJUČIVO
        // root: da je označen i neki unutarnji element, potpis nad njim mogao bi se podmetnuti.
        root.setIdAttribute("Id", true);

        Element signature = signatureOf(root);
        try {
            DOMValidateContext ctx = new DOMValidateContext(new PinnedCertificateSelector(signerCert), signature);
            // FINA potpisuje SHA-1 digestom (v. primjer poruke), a secure validation ga odbija već
            // pri unmarshalu. Povjerenje ostaje kriptografski pinano (PinnedCertificateSelector), a
            // ostala ograničenja secure validationa zamjenjuje requireExpectedShape.
            ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
            XMLSignature sig = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
            requireExpectedShape(sig.getSignedInfo(), rootId);
            if (!sig.validate(ctx)) {
                throw failure("XML potpis nije valjan");
            }
        } catch (ExternalRegistryException e) {
            throw e;
        } catch (Exception e) {
            throw failure("provjera XML potpisa nije uspjela: " + rootMessage(e), e);
        }
        return root;
    }

    /**
     * Oblik potpisa prije izvršavanja: jedina referenca je root, a kanonikalizacija, algoritam
     * potpisa, digest i transformacije su s popisa. Unmarshal ništa ne izvršava; {@code validate()}
     * izvršava transformacije, pa ovo mora biti prije njega.
     */
    private static void requireExpectedShape(SignedInfo signedInfo, String rootId) {
        @SuppressWarnings("unchecked")
        List<Reference> references = signedInfo.getReferences();
        if (references.size() != 1 || !("#" + rootId).equals(references.get(0).getURI())) {
            throw failure("potpis ne pokriva cijeli odgovor");
        }
        Reference reference = references.get(0);
        requireAllowed(CANONICALIZATIONS, signedInfo.getCanonicalizationMethod().getAlgorithm(), "kanonikalizacija");
        requireAllowed(SIGNATURE_METHODS, signedInfo.getSignatureMethod().getAlgorithm(), "algoritam potpisa");
        requireAllowed(DIGESTS, reference.getDigestMethod().getAlgorithm(), "digest");
        @SuppressWarnings("unchecked")
        List<Transform> transforms = reference.getTransforms();
        if (transforms.size() > MAX_TRANSFORMS) {
            throw failure("potpis ima previše transformacija");
        }
        for (Transform transform : transforms) {
            requireAllowed(TRANSFORMS, transform.getAlgorithm(), "transformacija");
        }
    }

    private static void requireAllowed(Set<String> allowed, String algorithm, String what) {
        if (!allowed.contains(algorithm)) {
            throw failure("potpis koristi nedopušten algoritam (" + what + "): " + algorithm);
        }
    }

    /** Jedini dopušteni položaj potpisa: {@code root/Signatures/Signature}. */
    private static Element signatureOf(Element root) {
        NodeList all = root.getOwnerDocument().getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (all.getLength() != 1) {
            throw failure(all.getLength() == 0
                    ? "odgovor nije potpisan" + unverifiedErrorCodes(root)
                    : "odgovor ima više potpisa");
        }
        Element signature = (Element) all.item(0);
        Node parent = signature.getParentNode();
        if (!(parent instanceof Element p) || !"Signatures".equals(p.getLocalName()) || p.getParentNode() != root) {
            throw failure("potpis nije na očekivanom mjestu");
        }
        return signature;
    }

    /**
     * Šifre iz nepotpisanog odgovora — FINA možda baš grešku registracije (100) vraća bez potpisa.
     * Samo za dijagnostiku: odgovor se svejedno odbija i šifra se ne koristi za odluku.
     */
    private static String unverifiedErrorCodes(Element root) {
        List<String> codes = new ArrayList<>();
        for (Element error : descendants(root, "Error")) {
            String code = text(error, "Code");
            if (code != null && code.length() <= 10) {
                codes.add(code);
            }
        }
        return codes.isEmpty() ? "" : " — nepotpisane šifre grešaka (samo za dijagnostiku): " + codes;
    }

    // ── dijagnostika ─────────────────────────────────────────────────────────

    private static final int SHAPE_MAX_DEPTH = 8;
    private static final int SHAPE_MAX_LENGTH = 2000;
    private static final int PREVIEW_BYTES = 120;

    /**
     * Stablo lokalnih naziva elemenata, bez teksta i atributa — npr.
     * {@code SignedAuthorizationUnionPermissionResponse[Person[OIB,FirstName,LastName],LegalTo[…],…]}.
     * Uzastopna braća istog naziva sažimaju se u {@code Function×2}.
     */
    static String shape(Element root) {
        StringBuilder sb = new StringBuilder();
        appendShape(sb, root, 0);
        return sb.length() > SHAPE_MAX_LENGTH ? sb.substring(0, SHAPE_MAX_LENGTH) + "…" : sb.toString();
    }

    private static void appendShape(StringBuilder sb, Element e, int depth) {
        sb.append(e.getLocalName());
        List<Element> children = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                children.add(c);
            }
        }
        if (children.isEmpty()) {
            return;
        }
        if ("Signatures".equals(e.getLocalName()) || depth >= SHAPE_MAX_DEPTH) {
            sb.append("[…]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < children.size(); ) {
            Element c = children.get(i);
            int run = 1;
            while (i + run < children.size() && c.getLocalName().equals(children.get(i + run).getLocalName())) {
                run++;
            }
            if (i > 0) {
                sb.append(',');
            }
            appendShape(sb, c, depth + 1);
            if (run > 1) {
                sb.append('×').append(run);
            }
            i += run;
        }
        sb.append(']');
    }

    /** Početak tijela koje nije XML (npr. HTML stranica proxyja), bez kontrolnih znakova. */
    private static String preview(byte[] xml) {
        String head = new String(xml, 0, Math.min(xml.length, PREVIEW_BYTES), StandardCharsets.UTF_8);
        return "\"" + head.replaceAll("\\p{Cntrl}", " ") + "\"";
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    // ── sadržaj ──────────────────────────────────────────────────────────────

    private static void failOnErrors(Element root) {
        Element errors = child(root, "Errors");
        if (errors == null) {
            return;
        }
        for (Element error : descendants(errors, "Error")) {
            String code = text(error, "Code");
            Optional<RuntimeException> rejection = EOvlastenjaErrorCodes.rejection(code, text(error, "Message"));
            if (rejection.isPresent()) {
                throw rejection.get();
            }
            // 403/404 (e-Punomoći isključene, preskočeni zapisi bez privole) ne tiču se e-Zastupanja.
            // Bez poruke: kod preskočenih zapisa može navoditi subjekte.
            if (code != null) {
                log.info("eovlastenja_info code={}", code);
            }
        }
    }

    /**
     * Subjekt iz odgovora mora biti traženi: {@code EntityFor} (u ime koga se djeluje) i
     * {@code LegalTo} (u okviru koga), koji god je naveden. Barem jedan mora biti naveden.
     */
    private static String verifySubject(Element root, String expectedLegalOib) {
        String name = null;
        int seen = 0;
        for (Element holder : new Element[]{child(root, "EntityFor"), child(root, "LegalTo")}) {
            if (holder == null) {
                continue;
            }
            Element legal = "EntityFor".equals(holder.getLocalName()) ? child(holder, "Legal") : holder;
            if (legal == null) {
                throw failure("odgovor ne djeluje za pravnu osobu");
            }
            Element jips = child(legal, "Jips");
            String ips = jips != null ? text(jips, "IPS") : null;
            String izvor = jips != null ? text(jips, "IZVOR_REG") : null;
            if (!expectedLegalOib.equals(ips) || !"1".equals(izvor)) {
                throw failure("subjekt u odgovoru nije traženi subjekt");
            }
            if (name == null) {
                name = text(legal, "Name");
            }
            seen++;
        }
        if (seen == 0) {
            throw failure("odgovor ne navodi subjekt");
        }
        // Naziv je iznajmljivač na PDF-u i aktima; bez njega bi se ispisalo ime zastupnika uz OIB tvrtke.
        if (name == null) {
            throw failure("odgovor ne navodi naziv subjekta");
        }
        return name;
    }

    private static List<Zastupanje.Funkcija> functions(Element representation) {
        List<Zastupanje.Funkcija> functions = new ArrayList<>();
        if (representation == null) {
            return functions;
        }
        for (Element function : descendants(representation, "Function")) {
            String code = text(function, "Code");
            String name = text(function, "Name");
            if (code != null || name != null) {
                functions.add(new Zastupanje.Funkcija(code, name, text(function, "Source")));
            }
        }
        return functions;
    }

    // ── DOM pomoćne ──────────────────────────────────────────────────────────

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<Element> descendants(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList all = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < all.getLength(); i++) {
            out.add((Element) all.item(i));
        }
        return out;
    }

    private static String text(Element parent, String localName) {
        Element e = child(parent, localName);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }

    private static ExternalRegistryException failure(String message) {
        return new ExternalRegistryException(REGISTRY, "e-Ovlaštenja: " + message);
    }

    private static ExternalRegistryException failure(String message, Throwable cause) {
        return new ExternalRegistryException(REGISTRY, "e-Ovlaštenja: " + message, cause);
    }

    /**
     * Vraća isključivo javni ključ pinanog certifikata, i to samo ako {@code KeyInfo} navodi
     * točno taj certifikat (byte-for-byte). Ključ iz dokumenta nikad se ne koristi — inače bi
     * napadač potpisao vlastitim ključem uz certifikat koji samo DN-om tvrdi da je FINA-in.
     */
    private static final class PinnedCertificateSelector extends KeySelector {

        private final X509Certificate trusted;

        PinnedCertificateSelector(X509Certificate trusted) {
            this.trusted = trusted;
        }

        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method,
                                        XMLCryptoContext context) throws KeySelectorException {
            List<String> seen = new ArrayList<>();
            if (keyInfo != null) {
                for (Object item : keyInfo.getContent()) {
                    if (item instanceof X509Data data) {
                        for (Object x509 : data.getContent()) {
                            if (x509 instanceof X509Certificate cert) {
                                if (trusted.equals(cert)) {
                                    PublicKey key = trusted.getPublicKey();
                                    return () -> key;
                                }
                                seen.add(describe(cert));
                            }
                        }
                    }
                }
            }
            // Npr. FINA je zamijenila potpisni certifikat — iz poruke se vidi koji je sad u upotrebi.
            throw new KeySelectorException("potpis ne navodi pinani certifikat e-Ovlaštenja; KeyInfo: "
                    + (seen.isEmpty() ? "bez X509 certifikata" : seen) + "; pinan: " + describe(trusted));
        }

        private static String describe(X509Certificate cert) {
            return cert.getSubjectX500Principal().getName() + " (serial " + cert.getSerialNumber().toString(16)
                    + ", do " + cert.getNotAfter().toInstant() + ")";
        }
    }
}
