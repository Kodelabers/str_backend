package com.str.backend.egop;

import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopParseException;
import com.str.backend.egop.exception.EgopTransportException;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPismeno;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPrilog;
import hr.infodom.egov.pismeno.DohvatiListuPrilogaPismena;
import hr.infodom.egov.pismeno.DohvatiPodatkePismena;
import hr.infodom.egov.pismeno.DokumentInfo;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismeno;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokument;
import hr.infodom.egov.pismeno.ObrisiDokumentPriloga;
import hr.infodom.egov.pismeno.ObrisiDokumentZaPismeno;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
import hr.infodom.egov.pismeno.PismenoInfo;
import hr.infodom.egov.pismeno.PriloziPismenaInfo;
import hr.infodom.egov.pismeno.StornirajPismeno;
import hr.infodom.egov.predmet.DohvatiPismenaPredmeta;
import hr.infodom.egov.predmet.DohvatiPodatkePredmeta;
import hr.infodom.egov.predmet.DohvatiPredmetId;
import hr.infodom.egov.predmet.DohvatiPredmeteZaKorisnikaURjesavanju;
import hr.infodom.egov.predmet.KreirajPredmet2;
import hr.infodom.egov.predmet.OdrediRjesavatelja;
import hr.infodom.egov.predmet.PismenoInfo2;
import hr.infodom.egov.predmet.PredmetBasicInfo2;
import hr.infodom.egov.predmet.PredmetBasicInfo3;
import hr.infodom.egov.predmet.PredmetInfo;
import hr.infodom.egov.predmet.PredmetInfo2;
import hr.infodom.egov.predmet.StornirajPredmet;
import hr.infodom.egov.predmet.ZatvoriPredmet;
import hr.infodom.egov.subjekt.DohvatiPodatkeSubjekta;
import hr.infodom.egov.subjekt.KreirajSubjekta;
import hr.infodom.egov.subjekt.SubjektInfo;

import java.util.List;
import java.util.Map;

/**
 * eGOP10 SOAP klijent — port InfoDomovog referentnog klijenta za STR.
 * Implementacije: {@link EgopClientImpl} (enabled=true) i {@link EgopClientMock}.
 */
public interface EgopClient {

    // Šifrarnici (učitani sa ServiceMDM-a pri startu; ključ = naziv)
    Map<String, String> getVrstePoslovnihSubjekata();

    Map<String, Integer> getUstroj();

    Map<String, String> getVrstePredmeta();

    Map<String, String> getVrstePismena();

    Map<String, Integer> getVrstePriloga();

    void healthCheck() throws EgopBadRequestException, EgopTransportException;

    /**
     * Provjera da li subjekt postoji u eGOP-u, za slučaj da ga treba kreirati.
     */
    SubjektInfo dohvatiPodatkeSubjekta(DohvatiPodatkeSubjekta request) throws EgopTransportException,
            EgopBadRequestException, EgopParseException;

    /**
     * Kreiraj subjekta u svrhu kreiranja predmeta.
     */
    SubjektInfo kreirajSubjekta(KreirajSubjekta request) throws EgopTransportException,
            EgopBadRequestException, EgopParseException;

    /**
     * Koristi se umjesto KreirajPredmet jer vraća klasu predmeta.
     */
    PredmetBasicInfo2 kreirajPredmet2(KreirajPredmet2 request) throws EgopTransportException,
            EgopBadRequestException;

    List<PredmetInfo2> dohvatiPredmeteZaKorisnikaURjesavanju(DohvatiPredmeteZaKorisnikaURjesavanju request)
            throws EgopTransportException, EgopParseException, EgopBadRequestException;

    boolean stornirajPredmet(StornirajPredmet request) throws EgopTransportException,
            EgopBadRequestException;

    boolean zatvoriPredmet(ZatvoriPredmet request) throws EgopTransportException,
            EgopBadRequestException;

    /**
     * KreirajPismeno2 — jedini koji vraća UrBroj.
     */
    PismenoBasicInfo2 kreirajPismeno2(KreirajPismeno2 request) throws EgopTransportException,
            EgopBadRequestException;

    void stornirajPismeno(StornirajPismeno request) throws EgopTransportException,
            EgopBadRequestException;

    /**
     * Dodavanje binarne datoteke na pismeno.
     */
    DokumentInfo kreirajDokumentZaPismeno(KreirajDokumentZaPismeno request) throws EgopTransportException,
            EgopBadRequestException;

    /**
     * Dodavanje priloga pismenu.
     */
    DokumentInfo kreirajPrilogIPridruziDokument(KreirajPrilogIPridruziDokument request)
            throws EgopTransportException, EgopBadRequestException;

    /**
     * Postavljanje rješavatelja na predmet.
     */
    void odrediRjesavatelja(OdrediRjesavatelja request) throws EgopTransportException, EgopBadRequestException;

    /**
     * Sva pismena na predmetu.
     */
    List<PismenoInfo2> dohvatiPismenaPredmeta(DohvatiPismenaPredmeta request) throws EgopTransportException,
            EgopBadRequestException, EgopParseException;

    /**
     * Dohvati ID predmeta u eGOP-u.
     */
    PredmetBasicInfo3 dohvatiPredmet(DohvatiPredmetId request) throws EgopBadRequestException, EgopTransportException;

    /**
     * Osnovni podaci o predmetu u eGOP-u.
     */
    PredmetInfo dohvatiPodatkePredmeta(DohvatiPodatkePredmeta request) throws EgopBadRequestException,
            EgopTransportException;

    /**
     * Detalji pismena.
     */
    PismenoInfo dohvatiPodatkePismena(DohvatiPodatkePismena request) throws EgopTransportException,
            EgopBadRequestException;

    /**
     * Info o glavnom dokumentu na pismenu.
     */
    DokumentInfo dohvatiDokumentZaPismeno(DohvatiDokumentZaPismeno request) throws EgopBadRequestException,
            EgopTransportException;

    /**
     * Sadržaj elektroničkog dokumenta priloga (jop pismena + rbrPriloga).
     */
    DokumentInfo dohvatiDokumentZaPrilog(DohvatiDokumentZaPrilog request) throws EgopBadRequestException,
            EgopTransportException;

    /**
     * Brisanje glavnog dokumenta iz pismena.
     */
    void obrisiDokumentZaPismeno(ObrisiDokumentZaPismeno request) throws EgopTransportException,
            EgopBadRequestException;

    /**
     * Svi prilozi pismena.
     */
    List<PriloziPismenaInfo> dohvatiListuPrilogaPismena(DohvatiListuPrilogaPismena request)
            throws EgopTransportException, EgopBadRequestException;

    void obrisiDokumentPriloga(ObrisiDokumentPriloga request) throws EgopTransportException,
            EgopBadRequestException;
}
