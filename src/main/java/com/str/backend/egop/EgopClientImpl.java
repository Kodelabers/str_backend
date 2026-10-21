package com.str.backend.egop;

import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopNotErrorCodes;
import com.str.backend.egop.exception.EgopParseException;
import com.str.backend.egop.exception.EgopTransportException;
import hr.infodom.egov.mdm.DohvatiVrstePismenaActive;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPismeno;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPismenoResponse;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPrilog;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPrilogResponse;
import hr.infodom.egov.pismeno.DohvatiListuPrilogaPismena;
import hr.infodom.egov.pismeno.DohvatiListuPrilogaPismenaResponse;
import hr.infodom.egov.pismeno.DohvatiPodatkePismena;
import hr.infodom.egov.pismeno.DohvatiPodatkePismenaResponse;
import hr.infodom.egov.pismeno.DokumentInfo;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismeno;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismenoResponse;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.KreirajPismeno2Response;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokument;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokumentResponse;
import hr.infodom.egov.pismeno.ListaPriloziPismenaInfo;
import hr.infodom.egov.pismeno.ObrisiDokumentPriloga;
import hr.infodom.egov.pismeno.ObrisiDokumentPrilogaResponse;
import hr.infodom.egov.pismeno.ObrisiDokumentZaPismeno;
import hr.infodom.egov.pismeno.ObrisiDokumentZaPismenoResponse;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
import hr.infodom.egov.pismeno.PismenoInfo;
import hr.infodom.egov.pismeno.PriloziPismenaInfo;
import hr.infodom.egov.pismeno.StornirajPismeno;
import hr.infodom.egov.pismeno.StornirajPismenoResponse;
import hr.infodom.egov.predmet.DohvatiPismenaPredmeta;
import hr.infodom.egov.predmet.DohvatiPismenaPredmetaResponse;
import hr.infodom.egov.predmet.DohvatiPodatkePredmeta;
import hr.infodom.egov.predmet.DohvatiPodatkePredmetaResponse;
import hr.infodom.egov.predmet.DohvatiPredmetId;
import hr.infodom.egov.predmet.DohvatiPredmetIdResponse;
import hr.infodom.egov.predmet.DohvatiPredmeteZaKorisnikaURjesavanju;
import hr.infodom.egov.predmet.DohvatiPredmeteZaKorisnikaURjesavanjuResponse;
import hr.infodom.egov.predmet.KreirajPredmet2;
import hr.infodom.egov.predmet.KreirajPredmet2Response;
import hr.infodom.egov.predmet.OdrediRjesavatelja;
import hr.infodom.egov.predmet.OdrediRjesavateljaResponse;
import hr.infodom.egov.predmet.PismenoInfo2;
import hr.infodom.egov.predmet.PredmetBasicInfo2;
import hr.infodom.egov.predmet.PredmetBasicInfo3;
import hr.infodom.egov.predmet.PredmetInfo;
import hr.infodom.egov.predmet.PredmetInfo2;
import hr.infodom.egov.predmet.StornirajPredmet;
import hr.infodom.egov.predmet.StornirajPredmetResponse;
import hr.infodom.egov.predmet.ZatvoriPredmet;
import hr.infodom.egov.predmet.ZatvoriPredmetResponse;
import hr.infodom.egov.subjekt.DohvatiPodatkeSubjekta;
import hr.infodom.egov.subjekt.DohvatiPodatkeSubjektaResponse;
import hr.infodom.egov.subjekt.KreirajSubjekta;
import hr.infodom.egov.subjekt.KreirajSubjektaResponse;
import hr.infodom.egov.subjekt.SubjektInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.core.SoapActionCallback;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.str.backend.egop.EgopSoapCallback.*;

@Component
@ConditionalOnProperty(name = "hr.infodom.str.integration.egop.enabled", havingValue = "true")
class EgopClientImpl implements EgopClient {

    private static final Logger log = LoggerFactory.getLogger(EgopClientImpl.class);

    private static final Set<Integer> NOT_ERRORS = Arrays.stream(EgopNotErrorCodes.values())
            .map(EgopNotErrorCodes::getCode)
            .collect(Collectors.toSet());

    /** Iznad ovoga poziv se logira kao WARN — kandidat za dizanje read timeouta. */
    private static final long SPORO_MS = 3_000L;

    private final EgopCodebooks egopCodebooks;
    private final WebServiceTemplate egopMDMWebServiceTemplate;
    private final WebServiceTemplate egopPismenoWebServiceTemplate;
    private final WebServiceTemplate egopPredmetWebServiceTemplate;
    private final WebServiceTemplate egopSubjektWebServiceTemplate;

    EgopClientImpl(EgopCodebooks egopCodebooks,
                   WebServiceTemplate egopMDMWebServiceTemplate,
                   WebServiceTemplate egopPismenoWebServiceTemplate,
                   WebServiceTemplate egopPredmetWebServiceTemplate,
                   WebServiceTemplate egopSubjektWebServiceTemplate) {
        this.egopCodebooks = egopCodebooks;
        this.egopMDMWebServiceTemplate = egopMDMWebServiceTemplate;
        this.egopPismenoWebServiceTemplate = egopPismenoWebServiceTemplate;
        this.egopPredmetWebServiceTemplate = egopPredmetWebServiceTemplate;
        this.egopSubjektWebServiceTemplate = egopSubjektWebServiceTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    void init() {
        log.info("Loading eGOP SOAP client...");
    }

    @Override
    public Map<String, String> getVrstePoslovnihSubjekata() {
        return egopCodebooks.vrsteSubjekata();
    }

    @Override
    public Map<String, Integer> getUstroj() {
        return egopCodebooks.ustroj();
    }

    @Override
    public Map<String, String> getVrstePredmeta() {
        return egopCodebooks.vrstePredmeta();
    }

    @Override
    public Map<String, String> getVrstePismena() {
        return egopCodebooks.vrstePismena();
    }

    @Override
    public Map<String, Integer> getVrstePriloga() {
        return egopCodebooks.vrstePriloga();
    }

    @Override
    public void healthCheck() throws EgopBadRequestException, EgopTransportException {
        call(egopMDMWebServiceTemplate, new DohvatiVrstePismenaActive(), MDM_DOHVATI_VRSTE_PISMENA_ACTIVE_SOAP_ACTION);
    }

    @Override
    public SubjektInfo dohvatiPodatkeSubjekta(DohvatiPodatkeSubjekta request) throws EgopTransportException,
            EgopBadRequestException, EgopParseException {
        var response = (DohvatiPodatkeSubjektaResponse) call(
                egopSubjektWebServiceTemplate, request, SUBJECT_DOHVATI_PODATKE_SUBJEKTA_SOAP_ACTION);
        return (SubjektInfo) handleErrors(response.getDohvatiPodatkeSubjektaResult());
    }

    @Override
    public SubjektInfo kreirajSubjekta(KreirajSubjekta request) throws EgopTransportException,
            EgopBadRequestException, EgopParseException {
        var response = (KreirajSubjektaResponse) call(
                egopSubjektWebServiceTemplate, request, SUBJECT_KREIRAJ_SUBJEKTA_SOAP_ACTION);
        return (SubjektInfo) handleErrors(response.getKreirajSubjektaResult());
    }

    @Override
    public PredmetBasicInfo2 kreirajPredmet2(KreirajPredmet2 request) throws EgopTransportException,
            EgopBadRequestException {
        var response = (KreirajPredmet2Response) call(
                egopPredmetWebServiceTemplate, request, PREDMET_KREIRAJ_PREDMET_2_SOAP_ACTION);
        return (PredmetBasicInfo2) handleErrors(response.getKreirajPredmet2Result());
    }

    @Override
    public List<PredmetInfo2> dohvatiPredmeteZaKorisnikaURjesavanju(DohvatiPredmeteZaKorisnikaURjesavanju request)
            throws EgopTransportException, EgopParseException, EgopBadRequestException {
        var response = (DohvatiPredmeteZaKorisnikaURjesavanjuResponse) call(
                egopPredmetWebServiceTemplate, request, PREDMET_DOHVATI_PREDMETE_ZA_KORISNIKA_U_RJESAVANJU_SOAP_ACTION);
        if (response.getDohvatiPredmeteZaKorisnikaURjesavanjuResult() == null
                || response.getDohvatiPredmeteZaKorisnikaURjesavanjuResult().getPredmetInfo2() == null) {
            throw new EgopParseException(
                    "Greška u parsiranju odgovora `DohvatiPredmeteZaKorisnikaURjesavanjuResponse` iz egopa, "
                            + "nedostaje element `PredmetBasicInfo2`");
        }
        for (PredmetInfo2 predmetInfo2 : response.getDohvatiPredmeteZaKorisnikaURjesavanjuResult().getPredmetInfo2()) {
            handleErrors(predmetInfo2);
        }
        return response.getDohvatiPredmeteZaKorisnikaURjesavanjuResult().getPredmetInfo2();
    }

    @Override
    public boolean stornirajPredmet(StornirajPredmet request) throws EgopTransportException, EgopBadRequestException {
        var response = (StornirajPredmetResponse) call(
                egopPredmetWebServiceTemplate, request, PREDMET_STORNIRAJ_PREDMET_SOAP_ACTION);
        return ((hr.infodom.egov.predmet.BaseInfo) handleErrors(response.getStornirajPredmetResult()))
                .isOperationSucceeded();
    }

    @Override
    public boolean zatvoriPredmet(ZatvoriPredmet request) throws EgopTransportException, EgopBadRequestException {
        var response = (ZatvoriPredmetResponse) call(
                egopPredmetWebServiceTemplate, request, PREDMET_ZATVORI_PREDMET_SOAP_ACTION);
        return ((hr.infodom.egov.predmet.BaseInfo) handleErrors(response.getZatvoriPredmetResult()))
                .isOperationSucceeded();
    }

    @Override
    public PismenoBasicInfo2 kreirajPismeno2(KreirajPismeno2 request) throws EgopTransportException,
            EgopBadRequestException {
        var response = (KreirajPismeno2Response) call(
                egopPismenoWebServiceTemplate, request, PISMENO_KREIRAJ_PISMENO_2_SOAP_ACTION);
        return (PismenoBasicInfo2) handleErrors(response.getKreirajPismeno2Result());
    }

    @Override
    public void stornirajPismeno(StornirajPismeno request) throws EgopTransportException, EgopBadRequestException {
        handleErrors(((StornirajPismenoResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_STORNIRAJ_PISMENO_SOAP_ACTION))
                .getStornirajPismenoResult());
    }

    @Override
    public DokumentInfo kreirajDokumentZaPismeno(KreirajDokumentZaPismeno request) throws EgopTransportException,
            EgopBadRequestException {
        var response = (KreirajDokumentZaPismenoResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_KREIRAJ_DOKUMENT_ZA_PISMENO_SOAP_ACTION);
        return (DokumentInfo) handleErrors(response.getKreirajDokumentZaPismenoResult());
    }

    @Override
    public DokumentInfo kreirajPrilogIPridruziDokument(KreirajPrilogIPridruziDokument request)
            throws EgopTransportException, EgopBadRequestException {
        var response = (KreirajPrilogIPridruziDokumentResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_KREIRAJ_PRILOG_I_PRIDRUZI_DOKUMENT_SOAP_ACTION);
        return (DokumentInfo) handleErrors(response.getKreirajPrilogIPridruziDokumentResult());
    }

    @Override
    public void odrediRjesavatelja(OdrediRjesavatelja request) throws EgopTransportException,
            EgopBadRequestException {
        handleErrors(((OdrediRjesavateljaResponse) call(
                egopPredmetWebServiceTemplate, request, PREDMET_ODREDI_RJESAVATELJA_SOAP_ACTION))
                .getOdrediRjesavateljaResult());
    }

    @Override
    public List<PismenoInfo2> dohvatiPismenaPredmeta(DohvatiPismenaPredmeta request) throws EgopTransportException,
            EgopBadRequestException, EgopParseException {
        var response = (DohvatiPismenaPredmetaResponse) call(
                egopPredmetWebServiceTemplate, request, PREDMET_DOHVATI_PISMENA_PREDMETA_SOAP_ACTION);
        if (response.getDohvatiPismenaPredmetaResult() == null
                || response.getDohvatiPismenaPredmetaResult().getPismenoInfo2() == null) {
            throw new EgopParseException(
                    "Greška u parsiranju odgovora `DohvatiPismenaPredmetaResponse` iz egopa, nedostaje element "
                            + "`PismenoInfo2`");
        }
        for (PismenoInfo2 pismenoInfo2 : response.getDohvatiPismenaPredmetaResult().getPismenoInfo2()) {
            handleErrors(pismenoInfo2);
        }
        return response.getDohvatiPismenaPredmetaResult().getPismenoInfo2();
    }

    @Override
    public PredmetBasicInfo3 dohvatiPredmet(DohvatiPredmetId request) throws EgopBadRequestException,
            EgopTransportException {
        // referentni klijent ovdje casta odgovor direktno u PredmetBasicInfo3 — to je
        // ClassCastException; stvarni odgovor je wrapper DohvatiPredmetIdResponse
        var response = (DohvatiPredmetIdResponse) call(
                egopPredmetWebServiceTemplate, request, PREDMET_DOHVATI_PREDMET_ID_SOAP_ACTION);
        return (PredmetBasicInfo3) handleErrors(response.getDohvatiPredmetIdResult());
    }

    @Override
    public PredmetInfo dohvatiPodatkePredmeta(DohvatiPodatkePredmeta request) throws EgopTransportException,
            EgopBadRequestException {
        var response = (DohvatiPodatkePredmetaResponse) call(
                egopPredmetWebServiceTemplate, request, PREDMET_DOHVATI_PODATKE_PREDMETA_SOAP_ACTION);
        return handleErrors(response.getDohvatiPodatkePredmetaResult());
    }

    @Override
    public PismenoInfo dohvatiPodatkePismena(DohvatiPodatkePismena request) throws EgopTransportException,
            EgopBadRequestException {
        var response = (DohvatiPodatkePismenaResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_DOHVATI_PODATKE_PISMENA_3_SOAP_ACTION);
        return (PismenoInfo) handleErrors(response.getDohvatiPodatkePismenaResult());
    }

    @Override
    public DokumentInfo dohvatiDokumentZaPismeno(DohvatiDokumentZaPismeno request) throws EgopBadRequestException,
            EgopTransportException {
        var response = (DohvatiDokumentZaPismenoResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_DOHVATI_DOKUMENT_ZA_PISMENO_SOAP_ACTION);
        return (DokumentInfo) handleErrors(response.getDohvatiDokumentZaPismenoResult());
    }

    @Override
    public DokumentInfo dohvatiDokumentZaPrilog(DohvatiDokumentZaPrilog request) throws EgopBadRequestException,
            EgopTransportException {
        var response = (DohvatiDokumentZaPrilogResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_DOHVATI_DOKUMENT_ZA_PRILOG_SOAP_ACTION);
        return (DokumentInfo) handleErrors(response.getDohvatiDokumentZaPrilogResult());
    }

    @Override
    public void obrisiDokumentZaPismeno(ObrisiDokumentZaPismeno request) throws EgopTransportException,
            EgopBadRequestException {
        handleErrors(((ObrisiDokumentZaPismenoResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_OBRISI_DOKUMENT_ZA_PISMENO_SOAP_ACTION))
                .getObrisiDokumentZaPismenoResult());
    }

    @Override
    public List<PriloziPismenaInfo> dohvatiListuPrilogaPismena(DohvatiListuPrilogaPismena request)
            throws EgopTransportException, EgopBadRequestException {
        var response = ((DohvatiListuPrilogaPismenaResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_DOHVATI_LISTU_PRILOGA_PISMENA_SOAP_ACTION))
                .getDohvatiListuPrilogaPismenaResult();
        return ((ListaPriloziPismenaInfo) handleErrors(response)).getLista().getPriloziPismenaInfo();
    }

    @Override
    public void obrisiDokumentPriloga(ObrisiDokumentPriloga request) throws EgopTransportException,
            EgopBadRequestException {
        handleErrors(((ObrisiDokumentPrilogaResponse) call(
                egopPismenoWebServiceTemplate, request, PISMENO_OBRISI_DOKUMENT_PRILOGA_SOAP_ACTION))
                .getObrisiDokumentPrilogaResult());
    }

    private Object handleErrors(hr.infodom.egov.pismeno.BaseInfo response) throws EgopBadRequestException {
        if (response.isOperationSucceeded()) {
            return response;
        }
        hr.infodom.egov.pismeno.ArrayOfErrorStatus errors = response.getErrors();
        if (errors == null || errors.getErrorStatus() == null || errors.getErrorStatus().isEmpty()) {
            throw new EgopBadRequestException("Greška u komunikaciji sa eGOP-om, eGOP nije vratio opis greške");
        }
        throw new EgopBadRequestException(errors.getErrorStatus().stream()
                .collect(Collectors.toMap(
                        hr.infodom.egov.pismeno.ErrorStatus::getErrorCode,
                        hr.infodom.egov.pismeno.ErrorStatus::getErrorMessage)));
    }

    private PredmetInfo handleErrors(PredmetInfo predmetInfo) throws EgopBadRequestException {
        if (predmetInfo.isOperationSucceeded()) {
            return predmetInfo;
        }
        hr.infodom.egov.predmet.ArrayOfErrorStatus errors = predmetInfo.getErrors();
        if (errors == null || errors.getErrorStatus() == null || errors.getErrorStatus().isEmpty()) {
            throw new EgopBadRequestException("Greška u komunikaciji sa eGOP-om, eGOP nije vratio opis greške");
        }
        // "Predmet ne postoji" (-100) je validan odgovor, ne greška
        if (errors.getErrorStatus().stream()
                .map(hr.infodom.egov.predmet.ErrorStatus::getErrorCode)
                .anyMatch(NOT_ERRORS::contains)) {
            return predmetInfo;
        }
        throw new EgopBadRequestException(errors.getErrorStatus().stream()
                .collect(Collectors.toMap(
                        hr.infodom.egov.predmet.ErrorStatus::getErrorCode,
                        hr.infodom.egov.predmet.ErrorStatus::getErrorMessage)));
    }

    private Object handleErrors(hr.infodom.egov.predmet.BaseInfo response) throws EgopBadRequestException {
        if (response.isOperationSucceeded()) {
            return response;
        }
        hr.infodom.egov.predmet.ArrayOfErrorStatus errors = response.getErrors();
        if (errors == null || errors.getErrorStatus() == null || errors.getErrorStatus().isEmpty()) {
            throw new EgopBadRequestException("Greška u komunikaciji sa eGOP-om, eGOP nije vratio opis greške");
        }
        if (errors.getErrorStatus().stream()
                .map(hr.infodom.egov.predmet.ErrorStatus::getErrorCode)
                .anyMatch(NOT_ERRORS::contains)) {
            return response;
        }
        throw new EgopBadRequestException(errors.getErrorStatus().stream()
                .collect(Collectors.toMap(
                        hr.infodom.egov.predmet.ErrorStatus::getErrorCode,
                        hr.infodom.egov.predmet.ErrorStatus::getErrorMessage)));
    }

    private void handleErrors(PismenoInfo2 pismenoInfo2) throws EgopBadRequestException {
        if (pismenoInfo2.isOperationSucceeded()) {
            return;
        }
        hr.infodom.egov.predmet.ArrayOfErrorStatus errors = pismenoInfo2.getErrors();
        if (errors == null || errors.getErrorStatus() == null || errors.getErrorStatus().isEmpty()) {
            throw new EgopBadRequestException("Greška u komunikaciji sa eGOP-om, eGOP nije vratio opis greške");
        }
        if (errors.getErrorStatus().stream()
                .map(hr.infodom.egov.predmet.ErrorStatus::getErrorCode)
                .anyMatch(NOT_ERRORS::contains)) {
            return;
        }
        throw new EgopBadRequestException(errors.getErrorStatus().stream()
                .collect(Collectors.toMap(
                        hr.infodom.egov.predmet.ErrorStatus::getErrorCode,
                        hr.infodom.egov.predmet.ErrorStatus::getErrorMessage)));
    }

    private void handleErrors(PredmetInfo2 predmetInfo2) throws EgopBadRequestException {
        if (predmetInfo2.isOperationSucceeded()) {
            return;
        }
        hr.infodom.egov.predmet.ArrayOfErrorStatus errors = predmetInfo2.getErrors();
        if (errors == null || errors.getErrorStatus() == null || errors.getErrorStatus().isEmpty()) {
            throw new EgopBadRequestException("Greška u komunikaciji sa eGOP-om, eGOP nije vratio opis greške");
        }
        if (errors.getErrorStatus().stream()
                .map(hr.infodom.egov.predmet.ErrorStatus::getErrorCode)
                .anyMatch(NOT_ERRORS::contains)) {
            return;
        }
        throw new EgopBadRequestException(errors.getErrorStatus().stream()
                .collect(Collectors.toMap(
                        hr.infodom.egov.predmet.ErrorStatus::getErrorCode,
                        hr.infodom.egov.predmet.ErrorStatus::getErrorMessage)));
    }

    private Object handleErrors(hr.infodom.egov.subjekt.BaseInfo response) throws EgopBadRequestException,
            EgopParseException {
        if (response.isOperationSucceeded()) {
            return response;
        }
        hr.infodom.egov.subjekt.ArrayOfErrorStatus errors = response.getErrors();
        if (errors == null || errors.getErrorStatus() == null || errors.getErrorStatus().isEmpty()) {
            throw new EgopParseException("Greška u komunikaciji sa eGOP-om, eGOP nije vratio opis greške");
        }
        // "Subjekt ne postoji" (-300) je validan odgovor, ne greška
        if (errors.getErrorStatus().stream()
                .map(hr.infodom.egov.subjekt.ErrorStatus::getErrorCode)
                .anyMatch(NOT_ERRORS::contains)) {
            return response;
        }
        throw new EgopBadRequestException(errors.getErrorStatus().stream()
                .collect(Collectors.toMap(
                        hr.infodom.egov.subjekt.ErrorStatus::getErrorCode,
                        hr.infodom.egov.subjekt.ErrorStatus::getErrorMessage)));
    }

    /**
     * Jedina točka kroz koju idu svi SOAP pozivi, pa je i jedino mjesto gdje se mjeri vrijeme.
     *
     * <p>Trajanje je ovdje najvažniji podatak: jedno urudžbiranje radi do sedam poziva sa
     * 15 s read timeoutom, a bez ovog retka iz loga se vidi samo da je cijeli tok pao —
     * ne i koji je poziv visio. Mock klijent logira svaki poziv, pa bi bez ovoga produkcija
     * bila tiša od test okruženja.
     *
     * <p>Prag {@link #SPORO_MS} razdvaja normalan poziv (DEBUG) od onog koji vrijedi
     * pogledati (WARN), da INFO ne postane šum pri normalnom radu.
     */
    private Object call(WebServiceTemplate template, Object request, SoapActionCallback callback)
            throws EgopTransportException {
        String operacija = request.getClass().getSimpleName();
        long pocetak = System.nanoTime();
        try {
            Object odgovor = template.marshalSendAndReceive(request, callback);
            long ms = trajanjeMs(pocetak);
            if (ms >= SPORO_MS) {
                log.warn("egop_call operacija={} trajanje_ms={} ishod=ok — sporo", operacija, ms);
            } else {
                log.debug("egop_call operacija={} trajanje_ms={} ishod=ok", operacija, ms);
            }
            return odgovor;
        } catch (WebServiceTransportException e) {
            log.error("egop_call operacija={} trajanje_ms={} ishod=transport_error: {}",
                    operacija, trajanjeMs(pocetak), e.getMessage());
            throw new EgopTransportException("Greška u komunikaciji sa eGOP servisom: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("egop_call operacija={} trajanje_ms={} ishod=error {}: {}",
                    operacija, trajanjeMs(pocetak), e.getClass().getSimpleName(), e.getMessage());
            throw new EgopTransportException("Nepoznata greška u komunikaciji sa eGOP servisom: " + e.getMessage(), e);
        }
    }

    private static long trajanjeMs(long pocetakNano) {
        return (System.nanoTime() - pocetakNano) / 1_000_000L;
    }
}
