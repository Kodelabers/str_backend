package com.str.backend.auth.nias;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.Marshaller;
import org.opensaml.core.xml.io.Unmarshaller;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.impl.IssuerBuilder;
import org.opensaml.saml.saml2.core.impl.LogoutResponseBuilder;
import org.opensaml.saml.saml2.core.impl.StatusBuilder;
import org.opensaml.saml.saml2.core.impl.StatusCodeBuilder;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.impl.SignatureBuilder;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.opensaml.xmlsec.signature.support.Signer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
public class NiasSoapLogoutController {

    private static final Logger log = LoggerFactory.getLogger(NiasSoapLogoutController.class);
    private static final String SAMLP_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String SOAP_ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private final RelyingPartyRegistrationRepository registrations;
    private final Saml2X509Credential spSigningCredential;

    public NiasSoapLogoutController(
            RelyingPartyRegistrationRepository registrations,
            Saml2X509Credential spSigningCredential) {
        this.registrations = registrations;
        this.spSigningCredential = spSigningCredential;
    }

    @PostMapping(
            value = "/logout/saml2/soap/" + NiasSamlConfig.REGISTRATION_ID,
            consumes = { MediaType.TEXT_XML_VALUE, "application/soap+xml" },
            produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> handle(@RequestBody String soapEnvelope) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document soapDoc = db.parse(new InputSource(new StringReader(soapEnvelope)));

        NodeList lrList = soapDoc.getElementsByTagNameNS(SAMLP_NS, "LogoutRequest");
        if (lrList.getLength() == 0) {
            log.warn("SOAP body did not contain LogoutRequest");
            return ResponseEntity.badRequest().build();
        }
        Element lrEl = (Element) lrList.item(0);

        Unmarshaller unmarshaller = XMLObjectProviderRegistrySupport
                .getUnmarshallerFactory().getUnmarshaller(lrEl);
        LogoutRequest logoutRequest = (LogoutRequest) unmarshaller.unmarshall(lrEl);

        RelyingPartyRegistration rp = registrations.findByRegistrationId(NiasSamlConfig.REGISTRATION_ID);
        String idpEntityId = rp.getAssertingPartyDetails().getEntityId();
        if (logoutRequest.getIssuer() == null
                || !idpEntityId.equals(logoutRequest.getIssuer().getValue())) {
            log.warn("LogoutRequest issuer does not match IdP entityId");
            return ResponseEntity.badRequest().build();
        }

        if (logoutRequest.getSignature() == null
                || !verifySignature(logoutRequest.getSignature(), rp)) {
            log.warn("LogoutRequest signature missing or invalid");
            return ResponseEntity.badRequest().build();
        }

        LogoutResponse response = buildLogoutResponse(logoutRequest, rp.getEntityId());
        signResponse(response);

        String responseXml = marshalToString(response);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_XML)
                .body(wrapInSoapEnvelope(responseXml));
    }

    private boolean verifySignature(Signature signature, RelyingPartyRegistration rp) {
        for (Saml2X509Credential cred : rp.getAssertingPartyDetails().getVerificationX509Credentials()) {
            try {
                SignatureValidator.validate(signature, new BasicX509Credential(cred.getCertificate()));
                return true;
            } catch (Exception ignore) {
            }
        }
        return false;
    }

    private LogoutResponse buildLogoutResponse(LogoutRequest req, String spEntityId) {
        LogoutResponse resp = new LogoutResponseBuilder().buildObject();
        resp.setID("_" + UUID.randomUUID());
        resp.setVersion(SAMLVersion.VERSION_20);
        resp.setIssueInstant(Instant.now());
        resp.setDestination(req.getIssuer().getValue());
        resp.setInResponseTo(req.getID());

        Issuer issuer = new IssuerBuilder().buildObject();
        issuer.setFormat(NameIDType.ENTITY);
        issuer.setValue(spEntityId);
        resp.setIssuer(issuer);

        StatusCode statusCode = new StatusCodeBuilder().buildObject();
        statusCode.setValue(StatusCode.SUCCESS);
        Status status = new StatusBuilder().buildObject();
        status.setStatusCode(statusCode);
        resp.setStatus(status);
        return resp;
    }

    private void signResponse(LogoutResponse response) throws Exception {
        BasicX509Credential cred = new BasicX509Credential(
                spSigningCredential.getCertificate(),
                spSigningCredential.getPrivateKey());

        Signature sig = new SignatureBuilder().buildObject(Signature.DEFAULT_ELEMENT_NAME);
        sig.setSigningCredential(cred);
        sig.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        sig.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        response.setSignature(sig);

        XMLObjectProviderRegistrySupport.getMarshallerFactory()
                .getMarshaller(response)
                .marshall(response);
        Signer.signObject(sig);
    }

    private String marshalToString(XMLObject obj) throws Exception {
        Element el = obj.getDOM();
        if (el == null) {
            Marshaller marshaller = XMLObjectProviderRegistrySupport
                    .getMarshallerFactory().getMarshaller(obj);
            el = marshaller.marshall(obj);
        }
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(el), new StreamResult(sw));
        return sw.toString();
    }

    private String wrapInSoapEnvelope(String responseXml) {
        return "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENV_NS + "\">"
                + "<soapenv:Body>"
                + responseXml
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
    }
}
