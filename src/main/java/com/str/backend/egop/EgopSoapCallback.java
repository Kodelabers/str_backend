package com.str.backend.egop;

import org.springframework.ws.soap.client.core.SoapActionCallback;

class EgopSoapCallback {

    // MDM
    static final SoapActionCallback MDM_LISTA_VRSTE_POSLOVNIH_SUBJEKATA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/ListaVrstePoslovnihSubjekata");
    static final SoapActionCallback MDM_DOHVATI_USTROJ_ACTIVE_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiUstrojActive");
    static final SoapActionCallback MDM_DOHVATI_VRSTE_PREDMETA_ACTIVE_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiVrstePredmetaActive");
    static final SoapActionCallback MDM_DOHVATI_VRSTE_PISMENA_ACTIVE_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiVrstePismenaActive");
    static final SoapActionCallback MDM_DOHVATI_VRSTE_PRILOGA_ACTIVE_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiVrstePrilogaActive");
    static final SoapActionCallback MDM_DOHVATI_USTROJ_ALL_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiUstrojAll");
    static final SoapActionCallback MDM_DOHVATI_VRSTE_PREDMETA_ALL_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiVrstePredmetaAll");
    static final SoapActionCallback MDM_DOHVATI_VRSTE_PISMENA_ALL_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiVrstePismenaAll");
    static final SoapActionCallback MDM_DOHVATI_VRSTE_PRILOGA_ALL_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiVrstePrilogaAll");

    // Pismeno
    static final SoapActionCallback PISMENO_KREIRAJ_PISMENO_2_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/KreirajPismeno2");
    static final SoapActionCallback PISMENO_STORNIRAJ_PISMENO_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/StornirajPismeno");
    static final SoapActionCallback PISMENO_KREIRAJ_DOKUMENT_ZA_PISMENO_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/KreirajDokumentZaPismeno");
    static final SoapActionCallback PISMENO_KREIRAJ_PRILOG_I_PRIDRUZI_DOKUMENT_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/KreirajPrilogIPridruziDokument");
    static final SoapActionCallback PISMENO_DOHVATI_PODATKE_PISMENA_3_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiPodatkePismena3");
    static final SoapActionCallback PISMENO_DOHVATI_DOKUMENT_ZA_PISMENO_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiDokumentZaPismeno");
    static final SoapActionCallback PISMENO_DOHVATI_DOKUMENT_ZA_PRILOG_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiDokumentZaPrilog");
    static final SoapActionCallback PISMENO_OBRISI_DOKUMENT_ZA_PISMENO_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/ObrisiDokumentZaPismeno");
    static final SoapActionCallback PISMENO_DOHVATI_LISTU_PRILOGA_PISMENA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiListuPrilogaPismena");
    static final SoapActionCallback PISMENO_OBRISI_DOKUMENT_PRILOGA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/ObrisiDokumentPriloga");

    // Predmet
    static final SoapActionCallback PREDMET_KREIRAJ_PREDMET_2_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/KreirajPredmet2");
    static final SoapActionCallback PREDMET_DOHVATI_PREDMETE_ZA_KORISNIKA_U_RJESAVANJU_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiPredmeteZaKorisnikaURjesavanju");
    static final SoapActionCallback PREDMET_DOHVATI_PREDMET_ID_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiPredmetId");
    static final SoapActionCallback PREDMET_DOHVATI_PODATKE_PREDMETA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiPodatkePredmeta");
    static final SoapActionCallback PREDMET_STORNIRAJ_PREDMET_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/StornirajPredmet");
    static final SoapActionCallback PREDMET_ZATVORI_PREDMET_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/ZatvoriPredmet");
    static final SoapActionCallback PREDMET_ODREDI_RJESAVATELJA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/OdrediRjesavatelja");
    static final SoapActionCallback PREDMET_DOHVATI_PISMENA_PREDMETA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiPismenaPredmeta");

    // Subjekt
    static final SoapActionCallback SUBJECT_DOHVATI_PODATKE_SUBJEKTA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/DohvatiPodatkeSubjekta");
    static final SoapActionCallback SUBJECT_KREIRAJ_SUBJEKTA_SOAP_ACTION = new SoapActionCallback(
            "http://www.infodom.hr/egov/KreirajSubjekta");

    private EgopSoapCallback() {
    }
}
