package com.str.backend.egop;

import com.str.backend.egop.exception.EgopBadRequestException;
import hr.infodom.egov.pismeno.ArrayOfErrorStatus;
import hr.infodom.egov.pismeno.BaseInfo;
import hr.infodom.egov.pismeno.DohvatiKPStatusPorukeSaVremenima;
import hr.infodom.egov.pismeno.DohvatiKPStatusPorukeSaVremenimaResponse;
import hr.infodom.egov.pismeno.ErrorStatus;
import hr.infodom.egov.pismeno.OtpremiPismenoUKorisnickiPretinac;
import hr.infodom.egov.pismeno.OtpremiPismenoUKorisnickiPretinacResponse;
import hr.infodom.egov.pismeno.OtpremnicaKPInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.core.SoapActionCallback;

import javax.xml.datatype.DatatypeFactory;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Preslikavanje KP metoda na DTO-e iz WSDL-a s testa (OtpremiPismenoUKorisnickiPretinac, …SaVremenima). */
class EgopClientImplKpTest {

    private final WebServiceTemplate pismeno = mock(WebServiceTemplate.class);
    private final EgopClientImpl client = new EgopClientImpl(mock(EgopCodebooks.class),
            mock(WebServiceTemplate.class), pismeno, mock(WebServiceTemplate.class), mock(WebServiceTemplate.class));

    @Test
    void otpremi_sendsByOibWithoutAttachments() throws Exception {
        when(pismeno.marshalSendAndReceive(any(Object.class), any(SoapActionCallback.class))).thenReturn(otpremaResponse(ok()));

        client.otpremiUKorisnickiPretinac("MINT\\str-svc", 1002, "98765432106", "Obavijest — HR1");

        ArgumentCaptor<Object> request = ArgumentCaptor.forClass(Object.class);
        verify(pismeno).marshalSendAndReceive(request.capture(),
                eq(EgopSoapCallback.PISMENO_OTPREMI_U_KORISNICKI_PRETINAC_SOAP_ACTION));
        OtpremiPismenoUKorisnickiPretinac sent = (OtpremiPismenoUKorisnickiPretinac) request.getValue();
        assertEquals("MINT\\str-svc", sent.getUsername());
        assertEquals(1002, sent.getJop());
        assertEquals("98765432106", sent.getSubjektOIBPrimatelja());
        assertNull(sent.getSubjektOznakaPrimatelja(), "primatelj ide po OIB-u, ne po oznaci subjekta");
        assertNull(sent.getJedinstveniIdentifikatorPismena());
        assertEquals("Obavijest — HR1", sent.getNapomena());
        assertFalse(sent.isOtpremaPriloga());
    }

    @Test
    void otpremi_businessRuleViolation_throwsWithEgopMessage() {
        BaseInfo failed = new BaseInfo();
        failed.setOperationSucceeded(false);
        ErrorStatus error = new ErrorStatus();
        error.setErrorCode(-1);
        error.setErrorMessage("Primatelj nema korisnički pretinac");
        ArrayOfErrorStatus errors = new ArrayOfErrorStatus();
        errors.getErrorStatus().add(error);
        failed.setErrors(errors);
        when(pismeno.marshalSendAndReceive(any(Object.class), any(SoapActionCallback.class))).thenReturn(otpremaResponse(failed));

        EgopBadRequestException e = assertThrows(EgopBadRequestException.class,
                () -> client.otpremiUKorisnickiPretinac("MINT\\str-svc", 1002, "98765432106", null));
        assertEquals("Primatelj nema korisnički pretinac", e.getMessage());
    }

    /** Bez rezultata se ne zna je li poruka otišla — mora biti neuspjeh, ne tihi uspjeh. */
    @Test
    void otpremi_missingResult_throws() {
        when(pismeno.marshalSendAndReceive(any(Object.class), any(SoapActionCallback.class))).thenReturn(otpremaResponse(null));

        assertThrows(EgopBadRequestException.class,
                () -> client.otpremiUKorisnickiPretinac("MINT\\str-svc", 1002, "98765432106", null));
    }

    @Test
    void dohvatiStatus_mapsStatusAndTimes() throws Exception {
        var dt = DatatypeFactory.newInstance();
        OtpremnicaKPInfo info = new OtpremnicaKPInfo();
        info.setOperationSucceeded(true);
        info.setStatusPoruke((short) 2);
        info.setDatumVrijemeKreiranja(dt.newXMLGregorianCalendar("2026-10-19T10:00:00"));
        info.setDatumVrijemeSlanja(dt.newXMLGregorianCalendar("2026-10-19T10:01:00"));
        info.setDatumVrijemeZaprimanjaPrimatelj(dt.newXMLGregorianCalendar("2026-10-19T12:00:00"));
        DohvatiKPStatusPorukeSaVremenimaResponse response = new DohvatiKPStatusPorukeSaVremenimaResponse();
        response.setDohvatiKPStatusPorukeSaVremenimaResult(info);
        when(pismeno.marshalSendAndReceive(any(Object.class), any(SoapActionCallback.class))).thenReturn(response);

        KpStatusPoruke kp = client.dohvatiKpStatusPoruke("MINT\\str-svc", 1002, "98765432106");

        ArgumentCaptor<Object> request = ArgumentCaptor.forClass(Object.class);
        verify(pismeno).marshalSendAndReceive(request.capture(),
                eq(EgopSoapCallback.PISMENO_DOHVATI_KP_STATUS_PORUKE_SA_VREMENIMA_SOAP_ACTION));
        assertEquals("98765432106", ((DohvatiKPStatusPorukeSaVremenima) request.getValue()).getSubjektOIBPrimatelja());
        assertEquals(2, kp.statusPoruke());
        assertEquals(Instant.parse("2026-10-19T08:01:00Z"), kp.poslano());
        assertEquals(Instant.parse("2026-10-19T10:00:00Z"), kp.zaprimljenoPrimatelj());
        assertNull(kp.zaprimljenoNias());
    }

    private static BaseInfo ok() {
        BaseInfo info = new BaseInfo();
        info.setOperationSucceeded(true);
        return info;
    }

    private static OtpremiPismenoUKorisnickiPretinacResponse otpremaResponse(BaseInfo result) {
        OtpremiPismenoUKorisnickiPretinacResponse response = new OtpremiPismenoUKorisnickiPretinacResponse();
        response.setOtpremiPismenoUKorisnickiPretinacResult(result);
        return response;
    }
}
