package com.str.backend.kp.client;

import com.str.backend.kp.KpPrimatelj;
import com.str.backend.kp.KpProperties;
import com.str.backend.kp.KpTimestamps;
import hr.apis.kp.gsb.ContentType;
import hr.apis.kp.gsb.ErrorType;
import hr.apis.kp.gsb.GsbEnvelopeType;
import hr.apis.kp.gsb.MessageHeaderType;
import hr.apis.kp.gsb.SendMessageRequest;
import hr.apis.kp.gsb.SendMessageResponse;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.WebServiceException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.core.SoapActionCallback;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pozivi SZP-a: jedna SOAP 1.1 operacija {@code sendMessage}, a usluga se bira elementom
 * {@code ServiceId} u zaglavlju omotnice. Poslovna poruka (sheme FO/PS) putuje u
 * {@code Content/Data}, ugniježđena kao XML ({@code EMBEDDED}) ili kao Base64 ({@code BASE64}) —
 * koje od toga APIS očekuje treba potvrditi, pa odlučuje {@code str.kp.data-encoding}, a odgovor
 * se čita u oba oblika.
 *
 * <p>Greške se čitaju na dvije razine: omotnica ({@code Errors}, šifre M…) i poslovni odgovor
 * ({@code Greske}/{@code Pogreske}). Odgovori se ne validiraju shemom: FO shema i specifikacija
 * se razilaze (npr. {@code SifraGreske} je u shemi {@code s[0-9]{3}}, a šifre su K001…), pa bi
 * stroga provjera odbacila upravo poruku o grešci.
 *
 * <p>U log ide ishod, ServiceId, šifre i trajanje — OIB primatelja i sadržaj ne.
 */
public class KpSoapClient implements KpClient {

    private static final Logger log = LoggerFactory.getLogger(KpSoapClient.class);

    static final SoapActionCallback SEND_MESSAGE =
            new SoapActionCallback("http://www.apis-it.hr/umu/2013/services/GSBService/sendMessage");
    static final SoapActionCallback ECHO =
            new SoapActionCallback("http://www.apis-it.hr/umu/2013/services/GSBService/echo");

    /** Spec 4.1 / 5.1 dopušta PDF; MIME tip potvrditi s APIS-om (K024). */
    static final String PDF_MIME = "application/pdf";
    static final String STATUS_OK = "OK";

    private static final JAXBContext PAYLOAD;
    private static final hr.apis.kp.gsb.ObjectFactory GSB = new hr.apis.kp.gsb.ObjectFactory();
    private static final hr.apis.kp.fo.ObjectFactory FO = new hr.apis.kp.fo.ObjectFactory();

    static {
        try {
            PAYLOAD = JAXBContext.newInstance("hr.apis.kp.fo:hr.apis.kp.ps");
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final WebServiceTemplate template;
    private final KpProperties properties;

    public KpSoapClient(WebServiceTemplate template, KpProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public KpOdgovor posalji(KpPoruka poruka) {
        return poruka.primatelj().tip() == KpPrimatelj.Tip.FO ? posaljiFo(poruka) : posaljiPs(poruka);
    }

    @Override
    public KpAktivnost aktivnost(KpPrimatelj primatelj, String uuidPoruke) {
        return primatelj.tip() == KpPrimatelj.Tip.FO
                ? aktivnostFo(primatelj, uuidPoruke)
                : aktivnostPs(primatelj, uuidPoruke);
    }

    @Override
    public String echo(String poruka) {
        try {
            Object response = template.marshalSendAndReceive(GSB.createEchoRequest(poruka), ECHO);
            return response instanceof JAXBElement<?> e ? String.valueOf(e.getValue()) : String.valueOf(response);
        } catch (WebServiceException e) {
            throw new KpTransportException("KP echo: " + e.getMessage(), e);
        }
    }

    // ---- FO: korisnički pretinac građana --------------------------------------------------

    private KpOdgovor posaljiFo(KpPoruka p) {
        hr.apis.kp.fo.ZaglavljeType z = new hr.apis.kp.fo.ZaglavljeType();
        z.setIdPosiljatelja(properties.idPosiljateljaFo().strip());
        z.setTipPoruke(BigInteger.valueOf(p.tipPoruke()));
        z.setIdPoruke(p.idPoruke().toString());
        z.setDatumVrijemeSlanja(KpTimestamps.xml(p.poslanoAt()));
        z.setRazinaSigurnosti(String.valueOf(properties.razinaSigurnosti()));
        z.setPorukaSPravnimUcinkom(tf(properties.isPravniUcinak()));
        z.setPotrebnaSuglasnostZaPregledPoruke(tf(properties.potrebnaSuglasnost()));
        z.setPotrebnaPotvrdaPrimitka(tf(properties.potrebnaPotvrda()));
        z.setPotrebanDSigPotvrde(tf(properties.potrebanDsig()));
        if (properties.isPravniUcinak()) {
            if (properties.traziRok()) {
                z.setRokDani(properties.rokDani());
                z.setNeradniDanURok(tf(properties.neradniDanURok()));
            }
            z.setTrajnoCuvanje(tf(properties.trajnoCuvanje()));
            if (!Boolean.TRUE.equals(properties.trajnoCuvanje())) {
                z.setGodineCuvanja(properties.godineCuvanja());
            }
        }
        if (klasaIUrBroj(p)) {
            z.setKlasaBrDok(p.klasa());
            z.setUrBroj(p.urBroj());
        }

        hr.apis.kp.fo.PorukaType poruka = new hr.apis.kp.fo.PorukaType();
        poruka.setPinPrimatelja(p.primatelj().id());
        poruka.setOznakaDrzave(KpProperties.DRZAVA_HR);
        poruka.setPredmet(p.naslov());
        poruka.setSadrzaj(base64(p.sadrzaj()));

        hr.apis.kp.fo.PrivitakType privitak = new hr.apis.kp.fo.PrivitakType();
        privitak.setNaziv(p.privitakNaziv());
        privitak.setMimeType(PDF_MIME);
        hr.apis.kp.fo.PrivitakType.Data data = new hr.apis.kp.fo.PrivitakType.Data();
        data.setEncoding("BASE64");
        data.getContent().add(Base64.getEncoder().encodeToString(p.privitakPdf()));
        privitak.setData(data);
        privitak.setJedinstveniIdentifikatorPismena(p.jedinstveniIdentifikatorPismena());
        hr.apis.kp.fo.PriviciType privici = new hr.apis.kp.fo.PriviciType();
        privici.getPrivitak().add(privitak);

        hr.apis.kp.fo.KorisnickiPretinacPorukaType kp = new hr.apis.kp.fo.KorisnickiPretinacPorukaType();
        kp.setZaglavlje(z);
        kp.setPoruka(poruka);
        kp.setPrivici(privici);

        Odziv odziv = send(KpProperties.SERVICE_SLANJE_FO, FO.createKorisnickiPretinacPoruka(kp));
        List<String> sifre = new ArrayList<>(odziv.sifre());
        List<String> opisi = new ArrayList<>(odziv.opisi());
        Instant obradeno = null;
        if (odziv.payload() != null) {
            hr.apis.kp.fo.OdgovorType odgovor = read(odziv.payload(), hr.apis.kp.fo.OdgovorType.class);
            obradeno = KpTimestamps.parse(odgovor.getDatumVrijemeObrade());
            if (odgovor.getGreske() != null) {
                for (hr.apis.kp.fo.GreskaType g : odgovor.getGreske().getGreska()) {
                    sifre.add(g.getSifraGreske());
                    opisi.add(opis(g.getSifraGreske(), g.getOpisGreske(), g.getUuidGreske()));
                }
            }
        }
        return new KpOdgovor(sifre, String.join("; ", opisi), obradeno, odziv.gsbId());
    }

    private KpAktivnost aktivnostFo(KpPrimatelj primatelj, String uuidPoruke) {
        hr.apis.kp.fo.DohvatiAktivnostPorukeType upit = new hr.apis.kp.fo.DohvatiAktivnostPorukeType();
        upit.setSenderId(Integer.parseInt(properties.senderId().strip()));
        upit.setIdPosiljatelja(properties.idPosiljateljaFo().strip());
        upit.setIdUpitaPosiljatelja(UUID.randomUUID().toString());
        upit.setTsSlanja(KpTimestamps.timestamp(Instant.now()));
        upit.setPinPrimatelja(primatelj.id());
        upit.setUuidPoruke(uuidPoruke);

        Odziv odziv = send(KpProperties.SERVICE_AKTIVNOST_FO, FO.createDohvatiAktivnostPorukeRequest(upit));
        if (odziv.payload() == null) {
            return aktivnostBezSadrzaja(odziv);
        }
        hr.apis.kp.fo.DohvatiAktivnostPorukeOdgovorType o =
                read(odziv.payload(), hr.apis.kp.fo.DohvatiAktivnostPorukeOdgovorType.class);
        List<String> sifre = new ArrayList<>(odziv.sifre());
        List<String> opisi = new ArrayList<>(odziv.opisi());
        if (o.getGreske() != null) {
            for (hr.apis.kp.fo.GreskaType g : o.getGreske().getGreska()) {
                sifre.add(g.getSifraGreske());
                opisi.add(opis(g.getSifraGreske(), g.getOpisGreske(), g.getUuidGreske()));
            }
        }
        neuspjehBezSifre(o.getStatusOdgovora(), sifre, opisi);
        return new KpAktivnost(sifre, String.join("; ", opisi), status(o.getStatusAktivnosti()),
                KpTimestamps.parse(o.getDatumIVrijemePrimitkaPoruke()),
                KpTimestamps.parse(o.getDatumIVrijemeAktivnosti()),
                KpTimestamps.parse(o.getIstekRoka()),
                o.getPdfPotvrdeElPotpisa());
    }

    // ---- PS: poslovni korisnički pretinac -------------------------------------------------

    private KpOdgovor posaljiPs(KpPoruka p) {
        hr.apis.kp.ps.ZaglavljeType z = new hr.apis.kp.ps.ZaglavljeType();
        z.setSenderId(Integer.parseInt(properties.senderId().strip()));
        z.setIdPosiljatelja(Integer.parseInt(properties.idPosiljateljaPs().strip()));
        z.setIdPorukeURegistru(p.tipPoruke());
        z.setIdPorukePosiljatelja(p.idPoruke().toString());
        z.setTsSlanja(KpTimestamps.timestamp(p.poslanoAt()));
        z.setRazinaSigurnosti(properties.razinaSigurnosti());
        z.setPorukaSPravnimUcinkom(tf(properties.isPravniUcinak()));
        z.setPotrebnaSuglasnostZaPregledPoruke(tf(properties.potrebnaSuglasnost()));
        z.setPotrebnaPotvrdaPrimitka(tf(properties.potrebnaPotvrda()));
        z.setPotrebanDSigPotvrde(tf(properties.potrebanDsig()));
        if (properties.isPravniUcinak()) {
            if (properties.traziRok()) {
                z.setRokDani(properties.rokDani());
                z.setNeradniDanURok(tf(properties.neradniDanURok()));
            } else {
                // Z023: poruka s pravnim učinkom bez potvrde i e-potpisa — rok 0, neradni dani F.
                z.setRokDani(0);
                z.setNeradniDanURok("F");
            }
            z.setTrajnoCuvanje(tf(properties.trajnoCuvanje()));
            if (!Boolean.TRUE.equals(properties.trajnoCuvanje())) {
                z.setGodineCuvanja(properties.godineCuvanja());
            }
        }
        if (klasaIUrBroj(p)) {
            z.setKlasaBrDok(p.klasa());
            z.setUrBroj(p.urBroj());
        }

        hr.apis.kp.ps.JipsType jips = new hr.apis.kp.ps.JipsType();
        jips.setIPS(p.primatelj().id());
        jips.setIzvorRegistar(p.primatelj().izvorRegistar());
        hr.apis.kp.ps.PorukaType poruka = new hr.apis.kp.ps.PorukaType();
        poruka.setJIPSPrimatelja(jips);
        poruka.setDrzava(KpProperties.DRZAVA_HR);
        poruka.setNaslov(p.naslov());
        // base64Binary — JAXB kodira sam; sadržaj su UTF-8 bajtovi teksta.
        poruka.setSadrzaj(p.sadrzaj().getBytes(StandardCharsets.UTF_8));

        hr.apis.kp.ps.PrivitakType privitak = new hr.apis.kp.ps.PrivitakType();
        privitak.setNaziv(p.privitakNaziv());
        privitak.setMimeType(PDF_MIME);
        privitak.setSadrzajPrivitka(p.privitakPdf());
        privitak.setJedinstveniIdentifikatorPismena(p.jedinstveniIdentifikatorPismena());
        hr.apis.kp.ps.PriviciType privici = new hr.apis.kp.ps.PriviciType();
        privici.getPrivitak().add(privitak);

        hr.apis.kp.ps.ZaprimiPorukuType zaprimi = new hr.apis.kp.ps.ZaprimiPorukuType();
        zaprimi.setZaglavlje(z);
        zaprimi.setPoruka(poruka);
        zaprimi.setPrivici(privici);
        hr.apis.kp.ps.ZaprimiPorukuRequest request = new hr.apis.kp.ps.ZaprimiPorukuRequest();
        request.setZaprimiPoruku(zaprimi);

        Odziv odziv = send(KpProperties.SERVICE_SLANJE_PS, request);
        List<String> sifre = new ArrayList<>(odziv.sifre());
        List<String> opisi = new ArrayList<>(odziv.opisi());
        Instant obradeno = null;
        if (odziv.payload() != null) {
            hr.apis.kp.ps.ZaprimiPorukuResponse response = read(odziv.payload(), hr.apis.kp.ps.ZaprimiPorukuResponse.class);
            hr.apis.kp.ps.ZaprimiPorukuOdgovorType o = response.getZaprimiPorukuOdgovor();
            if (o == null) {
                throw new KpTransportException("KP PS: ZaprimiPorukuResponse bez ZaprimiPorukuOdgovor");
            }
            obradeno = KpTimestamps.parse(o.getTsObrade());
            if (o.getPogreske() != null) {
                for (hr.apis.kp.ps.PogreskaType g : o.getPogreske().getPogreska()) {
                    sifre.add(g.getIndikatorPogreske());
                    opisi.add(opis(g.getIndikatorPogreske(), g.getPorukaPogreske(), g.getGUID()));
                }
            }
            neuspjehBezSifre(o.getStatusZaprimanja(), sifre, opisi);
        }
        return new KpOdgovor(sifre, String.join("; ", opisi), obradeno, odziv.gsbId());
    }

    private KpAktivnost aktivnostPs(KpPrimatelj primatelj, String uuidPoruke) {
        hr.apis.kp.ps.DohvatiAktivnostPorukeType upit = new hr.apis.kp.ps.DohvatiAktivnostPorukeType();
        upit.setSenderId(Integer.parseInt(properties.senderId().strip()));
        upit.setIdPosiljatelja(Integer.parseInt(properties.idPosiljateljaPs().strip()));
        upit.setIdUpitaPosiljatelja(UUID.randomUUID().toString());
        upit.setTsSlanja(KpTimestamps.timestamp(Instant.now()));
        upit.setIPS(primatelj.id());
        upit.setIzvorRegistar(primatelj.izvorRegistar());
        upit.setUuidPoruke(uuidPoruke);
        hr.apis.kp.ps.DohvatiAktivnostPorukeRequest request = new hr.apis.kp.ps.DohvatiAktivnostPorukeRequest();
        request.setDohvatiAktivnostPoruke(upit);

        Odziv odziv = send(KpProperties.SERVICE_AKTIVNOST_PS, request);
        if (odziv.payload() == null) {
            return aktivnostBezSadrzaja(odziv);
        }
        hr.apis.kp.ps.DohvatiAktivnostPorukeOdgovorType o =
                read(odziv.payload(), hr.apis.kp.ps.DohvatiAktivnostPorukeResponse.class)
                        .getDohvatiAktivnostPorukeOdgovor();
        if (o == null) {
            throw new KpTransportException("KP PS: DohvatiAktivnostPorukeResponse bez odgovora");
        }
        List<String> sifre = new ArrayList<>(odziv.sifre());
        List<String> opisi = new ArrayList<>(odziv.opisi());
        if (o.getPogreske() != null) {
            for (hr.apis.kp.ps.PogreskaType g : o.getPogreske().getPogreska()) {
                sifre.add(g.getIndikatorPogreske());
                opisi.add(opis(g.getIndikatorPogreske(), g.getPorukaPogreske(), g.getGUID()));
            }
        }
        neuspjehBezSifre(o.getStatusOdgovora(), sifre, opisi);
        return new KpAktivnost(sifre, String.join("; ", opisi), status(o.getStatusAktivnosti()),
                KpTimestamps.parse(o.getDatumIVrijemePrimitkaPoruke()),
                KpTimestamps.parse(o.getDatumIVrijemeAktivnosti()),
                KpTimestamps.parse(o.getIstekRoka()),
                o.getPdfPotvrdeElPotpisa());
    }

    // ---- omotnica --------------------------------------------------------------------------

    /**
     * Odgovor omotnice: šifre iz {@code Errors}, poslovni sadržaj iz {@code Content/Data} i
     * {@code GsbId} — identifikator koji SZP dodjeljuje poruci i po kojem helpdesk APIS-a traži
     * incident (spec. 3.1, 7).
     */
    record Odziv(List<String> sifre, List<String> opisi, Element payload, String gsbId) {
    }

    private Odziv send(String serviceId, Object payload) {
        UUID messageId = UUID.randomUUID();
        MessageHeaderType header = new MessageHeaderType();
        header.setSenderId(properties.senderId().strip());
        header.setServiceId(serviceId);
        header.setMessageId(messageId.toString());
        header.setSenderTimeStamp(KpTimestamps.xml(Instant.now()));

        ContentType content = new ContentType();
        content.setMimeType(properties.contentMimeType().strip());
        ContentType.Data data = new ContentType.Data();
        KpProperties.DataEncoding encoding = properties.encoding();
        data.setEncoding(encoding.name());
        Element element = marshal(payload);
        data.getContent().add(encoding == KpProperties.DataEncoding.EMBEDDED
                ? element
                : Base64.getEncoder().encodeToString(serialize(element)));
        content.setData(data);

        GsbEnvelopeType envelope = new GsbEnvelopeType();
        envelope.setMessageHeader(header);
        envelope.setContent(content);
        SendMessageRequest request = new SendMessageRequest();
        request.setGsbEnvelope(envelope);

        long start = System.nanoTime();
        SendMessageResponse response;
        try {
            response = (SendMessageResponse) template.marshalSendAndReceive(request, SEND_MESSAGE);
        } catch (WebServiceException | ClassCastException e) {
            log.warn("kp_call failed service={} message_id={} ms={}: {}", serviceId, messageId,
                    elapsedMs(start), e.getMessage());
            throw new KpTransportException("KP service " + serviceId + ": " + e.getMessage(), e);
        }
        if (response == null) {
            throw new KpTransportException("KP service " + serviceId + ": prazan odgovor");
        }

        List<String> sifre = new ArrayList<>();
        List<String> opisi = new ArrayList<>();
        if (response.getErrors() != null) {
            for (ErrorType error : response.getErrors().getError()) {
                sifre.add(error.getErrorCode());
                opisi.add(error.getErrorCode() + " " + error.getErrorMessage());
            }
        }
        Element result = response.getContent() == null ? null : payloadOf(response.getContent());
        MessageHeaderType odgovorHeader = response.getMessageHeader();
        String gsbId = odgovorHeader == null ? null : odgovorHeader.getGsbId();
        // Status razmjene (spec. 3.2): je li SZP poruku proslijedio usluzi. Vrijednosti spec ne
        // navodi, pa se samo bilježi — ishod određuju šifre grešaka i poslovni odgovor.
        String exchangeStatus = odgovorHeader == null ? null : odgovorHeader.getExchangeStatus();
        log.info("kp_call service={} message_id={} gsb_id={} exchange_status={} ms={} envelope_errors={} payload={}",
                serviceId, messageId, gsbId, exchangeStatus, elapsedMs(start), sifre, result != null);
        if (result == null && sifre.isEmpty()) {
            throw new KpTransportException("KP service " + serviceId + ": odgovor bez poslovnog sadržaja i bez šifre"
                    + " greške (gsb_id=" + gsbId + ", exchange_status=" + exchangeStatus + ")");
        }
        return new Odziv(sifre, opisi, result, gsbId);
    }

    /** Poslovni sadržaj odgovora — ugniježđeni element ili Base64 tekst. */
    static Element payloadOf(ContentType content) {
        if (content.getData() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object part : content.getData().getContent()) {
            if (part instanceof Element element) {
                return element;
            }
            if (part instanceof JAXBElement<?> jaxb && jaxb.getValue() instanceof Element element) {
                return element;
            }
            if (part instanceof String s) {
                text.append(s);
            }
        }
        String body = text.toString().strip();
        if (body.isEmpty()) {
            return null;
        }
        byte[] xml;
        try {
            xml = Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException notBase64) {
            // EMBEDDED kao escapirani tekst, ne kao element.
            xml = body.getBytes(StandardCharsets.UTF_8);
        }
        return parse(xml);
    }

    private static Element marshal(Object payload) {
        try {
            DOMResult result = new DOMResult(newDocument());
            Marshaller marshaller = PAYLOAD.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
            marshaller.marshal(payload, result);
            return ((Document) result.getNode()).getDocumentElement();
        } catch (JAXBException e) {
            throw new IllegalStateException("KP poruka se ne može serijalizirati: " + e.getMessage(), e);
        }
    }

    private static <T> T read(Element element, Class<T> type) {
        try {
            return PAYLOAD.createUnmarshaller().unmarshal(element, type).getValue();
        } catch (JAXBException e) {
            throw new KpTransportException("KP odgovor se ne može pročitati kao " + type.getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    private static byte[] serialize(Element element) {
        try {
            var transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            StringWriter out = new StringWriter();
            transformer.transform(new javax.xml.transform.dom.DOMSource(element),
                    new javax.xml.transform.stream.StreamResult(out));
            return out.toString().getBytes(StandardCharsets.UTF_8);
        } catch (javax.xml.transform.TransformerException e) {
            throw new IllegalStateException("KP poruka se ne može serijalizirati: " + e.getMessage(), e);
        }
    }

    private static Element parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = secureFactory();
            return factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new ByteArrayInputStream(xml)))
                    .getDocumentElement();
        } catch (Exception e) {
            throw new KpTransportException("KP odgovor (Data) nije ispravan XML: " + e.getMessage(), e);
        }
    }

    private static Document newDocument() {
        try {
            return secureFactory().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Odgovor dolazi izvana — bez DTD-a i vanjskih entiteta (XXE). */
    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    // ---- pomoćno ---------------------------------------------------------------------------

    /** Omotnica je odbijena (šifre M…), pa poslovnog odgovora nema. */
    private static KpAktivnost aktivnostBezSadrzaja(Odziv odziv) {
        return new KpAktivnost(odziv.sifre(), String.join("; ", odziv.opisi()), null, null, null, null, null);
    }

    /**
     * Status „Greska" (odnosno 1 — „NEUSPJEŠNO" po tablici FO) bez ijedne šifre ne smije proći
     * kao uspjeh.
     */
    private static void neuspjehBezSifre(String status, List<String> sifre, List<String> opisi) {
        if (status == null || !sifre.isEmpty()) {
            return;
        }
        String s = status.strip().toUpperCase(Locale.ROOT);
        if (!s.equals(STATUS_OK) && !s.equals("2")) {
            sifre.add("STATUS_" + s);
            opisi.add("status odgovora " + status + " bez šifre greške");
        }
    }

    /** Šifra, opis i GUID pojave greške — GUID traži helpdesk APIS-a. */
    private static String opis(String sifra, String opis, String guid) {
        return sifra + " " + opis + (guid == null || guid.isBlank() ? "" : " [guid " + guid + "]");
    }

    private static Integer status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** KlasaBrDok i UrBroj idu zajedno (K021) i samo ako stanu u 5–30 znakova sheme. */
    private static boolean klasaIUrBroj(KpPoruka p) {
        return fits(p.klasa()) && fits(p.urBroj());
    }

    private static boolean fits(String value) {
        return value != null && value.length() >= 5 && value.length() <= 30;
    }

    private static String tf(Boolean value) {
        return Boolean.TRUE.equals(value) ? "T" : "F";
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
