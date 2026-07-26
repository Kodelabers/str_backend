package com.str.backend.egop;

import com.str.backend.egop.exception.EgopException;
import com.str.backend.egop.exception.EgopTransportException;
import hr.infodom.egov.mdm.ArrayOfErrorStatus;
import hr.infodom.egov.mdm.BaseInfo;
import hr.infodom.egov.mdm.DohvatiUstrojActive;
import hr.infodom.egov.mdm.DohvatiUstrojActiveResponse;
import hr.infodom.egov.mdm.DohvatiVrstePismenaActive;
import hr.infodom.egov.mdm.DohvatiVrstePismenaActiveResponse;
import hr.infodom.egov.mdm.DohvatiVrstePredmetaActive;
import hr.infodom.egov.mdm.DohvatiVrstePredmetaActiveResponse;
import hr.infodom.egov.mdm.DohvatiVrstePrilogaActive;
import hr.infodom.egov.mdm.DohvatiVrstePrilogaActiveResponse;
import hr.infodom.egov.mdm.ErrorStatus;
import hr.infodom.egov.mdm.ListaVrstePoslovnihSubjekata;
import hr.infodom.egov.mdm.ListaVrstePoslovnihSubjekataResponse;
import hr.infodom.egov.mdm.TipOsobeInfo;
import hr.infodom.egov.mdm.UstrojInfo;
import hr.infodom.egov.mdm.VrstePismenaInfo;
import hr.infodom.egov.mdm.VrstePredmetaInfo;
import hr.infodom.egov.mdm.VrstePrilogaInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContextException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.core.SoapActionCallback;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.str.backend.egop.EgopSoapCallback.MDM_DOHVATI_USTROJ_ACTIVE_SOAP_ACTION;
import static com.str.backend.egop.EgopSoapCallback.MDM_DOHVATI_VRSTE_PISMENA_ACTIVE_SOAP_ACTION;
import static com.str.backend.egop.EgopSoapCallback.MDM_DOHVATI_VRSTE_PREDMETA_ACTIVE_SOAP_ACTION;
import static com.str.backend.egop.EgopSoapCallback.MDM_DOHVATI_VRSTE_PRILOGA_ACTIVE_SOAP_ACTION;
import static com.str.backend.egop.EgopSoapCallback.MDM_LISTA_VRSTE_POSLOVNIH_SUBJEKATA_SOAP_ACTION;

/**
 * Učitava eGOP šifrarnike sa ServiceMDM-a kao mape naziv → šifra.
 *
 * <p>Ponašanje kod nedostupnog eGOP-a ovisi o {@code str.egop.codebooks.fail-fast}:
 * uz {@code true} aplikacija se ne diže, uz {@code false} se diže s praznim
 * šifrarnicima. Fail-soft je namijenjen okruženjima gdje STR dijeli kutiju s drugim
 * servisima (CDU) — prolazni ispad eGOP-a tamo ne smije oboriti cijeli servis; pozivi
 * tada padaju pojedinačno kroz {@code resolveRequired}, submission ostaje {@code FAILED},
 * a {@link EgopRetryJob} ga pokupi kad se šifrarnici osvježe.
 *
 * <p>Mape su uvijek ne-null (prazna mapa kod neuspjeha) i nikad se ne prazne uspješno
 * učitane vrijednosti — neuspio refresh zadržava prethodne.
 */
@Component
@ConditionalOnProperty(name = "hr.infodom.str.integration.egop.enabled", havingValue = "true")
class EgopCodebooks {

    private static final Logger log = LoggerFactory.getLogger(EgopCodebooks.class);

    /** Najkraći razmak između dva pokušaja učitavanja na zahtjev (štiti MDM od navale). */
    private static final long RELOAD_THROTTLE_MS = 60_000;

    private volatile Map<String, String> vrsteSubjekata = Map.of();
    private volatile Map<String, Integer> ustroj = Map.of();
    private volatile Map<String, String> vrstePredmeta = Map.of();
    private volatile Map<String, String> vrstePismena = Map.of();
    private volatile Map<String, Integer> vrstePriloga = Map.of();

    private volatile long lastAttemptAt;

    private final WebServiceTemplate egopMDMWebServiceTemplate;
    private final String appUsername;
    private final boolean failFast;

    EgopCodebooks(WebServiceTemplate egopMDMWebServiceTemplate,
                  EgopProperties properties,
                  @Value("${str.egop.codebooks.fail-fast:true}") boolean failFast) {
        this.egopMDMWebServiceTemplate = egopMDMWebServiceTemplate;
        this.appUsername = properties.qualifiedAppUsername();
        this.failFast = failFast;
    }

    @PostConstruct
    void init() {
        log.info("eGOP app-username za dohvat šifrarnika: {}", appUsername);
        boolean complete = reload();
        if (!complete && failFast) {
            throw new ApplicationContextException(
                    "eGOP šifrarnici nisu učitani sa ServiceMDM-a. Postaviti"
                            + " str.egop.codebooks.fail-fast=false ako servis smije raditi bez njih.");
        }
        if (!complete) {
            log.error("egop_codebooks_incomplete — nastavljam bez svih šifrarnika (fail-fast=false);"
                    + " urudžbiranje će padati dok se ne osvježe");
        }
    }

    /** Periodično osvježavanje: prolazni ispad MDM-a se sam izliječi bez restarta,
     *  a nove vrste unesene na eGOP strani postaju vidljive bez deploya. */
    @Scheduled(cron = "${str.egop.codebooks.refresh-cron:0 0/30 * * * *}")
    void refresh() {
        reload();
    }

    Map<String, String> vrsteSubjekata() {
        ensureLoaded();
        return vrsteSubjekata;
    }

    Map<String, Integer> ustroj() {
        ensureLoaded();
        return ustroj;
    }

    Map<String, String> vrstePredmeta() {
        ensureLoaded();
        return vrstePredmeta;
    }

    Map<String, String> vrstePismena() {
        ensureLoaded();
        return vrstePismena;
    }

    Map<String, Integer> vrstePriloga() {
        ensureLoaded();
        return vrstePriloga;
    }

    /**
     * Ako je ijedan šifrarnik prazan (start uz fail-soft ili pad refresha), pokuša
     * ponovo — ali najviše jednom u {@link #RELOAD_THROTTLE_MS}, da niz neuspjelih
     * registracija ne pretvori svaki poziv u novi krug od pet MDM zahtjeva.
     */
    private void ensureLoaded() {
        if (!hasEmptyCodebook()) {
            return;
        }
        if (System.currentTimeMillis() - lastAttemptAt < RELOAD_THROTTLE_MS) {
            return;
        }
        reload();
    }

    private boolean hasEmptyCodebook() {
        return vrsteSubjekata.isEmpty() || ustroj.isEmpty() || vrstePredmeta.isEmpty()
                || vrstePismena.isEmpty() || vrstePriloga.isEmpty();
    }

    /**
     * Učitava svaki šifrarnik neovisno — djelomičan uspjeh je bolji od nikakvog, a
     * neuspio pojedinačni dohvat zadržava prethodno učitanu vrijednost.
     *
     * @return {@code true} ako su nakon poziva svi šifrarnici popunjeni
     */
    private synchronized boolean reload() {
        lastAttemptAt = System.currentTimeMillis();
        vrsteSubjekata = load("CODEBOOK_VRSTE_SUBJEKATA", vrsteSubjekata, this::loadVrsteSubjekata);
        ustroj = load("CODEBOOK_USTROJ", ustroj, this::loadUstroj);
        vrstePredmeta = load("CODEBOOK_VRSTE_PREDMETA", vrstePredmeta, this::loadVrstePredmeta);
        vrstePismena = load("CODEBOOK_VRSTE_PISMENA", vrstePismena, this::loadVrstePismena);
        vrstePriloga = load("CODEBOOK_VRSTE_PRILOGA", vrstePriloga, this::loadVrstePriloga);
        return !hasEmptyCodebook();
    }

    private <K, V> Map<K, V> load(String name, Map<K, V> current, Supplier<Map<K, V>> loader) {
        try {
            Map<K, V> loaded = loader.get();
            if (loaded.isEmpty()) {
                log.warn("egop_codebook_empty codebook={} — eGOP je vratio praznu listu", name);
                return current.isEmpty() ? loaded : current;
            }
            log.info("Loaded eGOP {} with {} entries", name, loaded.size());
            return loaded;
        } catch (RuntimeException e) {
            log.error("egop_codebook_load_failed codebook={}: {}", name, e.getMessage(), e);
            return current;
        }
    }

    private Map<String, String> loadVrsteSubjekata() {
        log.info("Loading CODEBOOK_VRSTE_SUBJEKATA from eGOP...");
        ListaVrstePoslovnihSubjekata request = new ListaVrstePoslovnihSubjekata();
        request.setUsername(appUsername);

        ListaVrstePoslovnihSubjekataResponse response = (ListaVrstePoslovnihSubjekataResponse) call(
                request, MDM_LISTA_VRSTE_POSLOVNIH_SUBJEKATA_SOAP_ACTION);

        // naziv → oznaka, kao i ostali šifrarnici (referentni klijent mapira obrnuto,
        // ali sam po nazivu izvlači ID — vidi VrstaPoslovnihSubjekata). MDM sadrži
        // duplikate naziva ("Trgovačko društvo" pod 5 i 10) pa se zadržava prva oznaka.
        return response.getListaVrstePoslovnihSubjekataResult()
                .getTipOsobeInfo()
                .stream()
                .peek(this::handleErrors)
                .collect(Collectors.toMap(TipOsobeInfo::getNaziv, TipOsobeInfo::getOznaka, (a, b) -> a));
    }

    private Map<String, Integer> loadUstroj() {
        log.info("Loading CODEBOOK_USTROJ from eGOP...");
        DohvatiUstrojActive request = new DohvatiUstrojActive();
        request.setUserName(appUsername);

        return ((DohvatiUstrojActiveResponse) call(request, MDM_DOHVATI_USTROJ_ACTIVE_SOAP_ACTION))
                .getDohvatiUstrojActiveResult()
                .getUstrojInfo()
                .stream()
                .peek(this::handleErrors)
                .collect(Collectors.toMap(UstrojInfo::getNazivorgjedinice, UstrojInfo::getIdorgjedinice,
                        (a, b) -> a));
    }

    private Map<String, String> loadVrstePredmeta() {
        log.info("Loading CODEBOOK_VRSTE_PREDMETA from eGOP...");
        DohvatiVrstePredmetaActive request = new DohvatiVrstePredmetaActive();
        request.setUserName(appUsername);

        return ((DohvatiVrstePredmetaActiveResponse) call(request, MDM_DOHVATI_VRSTE_PREDMETA_ACTIVE_SOAP_ACTION))
                .getDohvatiVrstePredmetaActiveResult()
                .getVrstePredmetaInfo()
                .stream()
                .peek(this::handleErrors)
                .collect(Collectors.toMap(
                        VrstePredmetaInfo::getNazivVrstePredmeta,
                        VrstePredmetaInfo::getIdvrste,
                        (a, b) -> a));
    }

    private Map<String, String> loadVrstePismena() {
        log.info("Loading CODEBOOK_VRSTE_PISMENA from eGOP...");
        DohvatiVrstePismenaActive request = new DohvatiVrstePismenaActive();
        request.setUserName(appUsername);

        return ((DohvatiVrstePismenaActiveResponse) call(request, MDM_DOHVATI_VRSTE_PISMENA_ACTIVE_SOAP_ACTION))
                .getDohvatiVrstePismenaActiveResult()
                .getVrstePismenaInfo()
                .stream()
                .peek(this::handleErrors)
                .collect(Collectors.toMap(
                        VrstePismenaInfo::getNazivVrstePismena,
                        VrstePismenaInfo::getIdVrstePismena,
                        (a, b) -> a));
    }

    private Map<String, Integer> loadVrstePriloga() {
        log.info("Loading CODEBOOK_VRSTE_PRILOGA from eGOP...");
        DohvatiVrstePrilogaActive request = new DohvatiVrstePrilogaActive();
        request.setUserName(appUsername);

        return ((DohvatiVrstePrilogaActiveResponse) call(request, MDM_DOHVATI_VRSTE_PRILOGA_ACTIVE_SOAP_ACTION))
                .getDohvatiVrstePrilogaActiveResult()
                .getVrstePrilogaInfo()
                .stream()
                .peek(this::handleErrors)
                .collect(Collectors.toMap(
                        VrstePrilogaInfo::getNazivVrstePriloga,
                        VrstePrilogaInfo::getIdVrstePriloga,
                        (a, b) -> a));
    }

    private void handleErrors(BaseInfo response) {
        if (response.isOperationSucceeded()) {
            return;
        }
        ArrayOfErrorStatus errors = response.getErrors();
        if (errors == null || errors.getErrorStatus() == null || errors.getErrorStatus().isEmpty()) {
            throw new IllegalStateException("Greška u komunikaciji sa eGOP-om, eGOP nije vratio opis greške");
        }
        throw new IllegalStateException(errors.getErrorStatus()
                .stream()
                .map(ErrorStatus::getErrorMessage)
                .collect(Collectors.joining(", ")));
    }

    /** Omotava provjereni {@link EgopException} u runtime iznimku da {@code load(...)}
     *  može jednim {@code catch}-em pokriti i transportne i sadržajne greške. */
    private Object call(Object request, SoapActionCallback callback) {
        try {
            return doCall(request, callback);
        } catch (EgopException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private Object doCall(Object request, SoapActionCallback callback) throws EgopTransportException {
        try {
            return egopMDMWebServiceTemplate.marshalSendAndReceive(request, callback);
        } catch (WebServiceTransportException e) {
            throw new EgopTransportException("Greška u komunikaciji sa eGOP servisom: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new EgopTransportException("Nepoznata greška u komunikaciji sa eGOP servisom: " + e.getMessage(), e);
        }
    }
}
