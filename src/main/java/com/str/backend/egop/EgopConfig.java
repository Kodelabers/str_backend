package com.str.backend.egop;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.HttpComponentsMessageSender;

import java.util.List;

/**
 * eGOP SOAP transport: SOAP 1.2 preko IIS-a s NTLM autentifikacijom.
 * Port InfoDomovog referentnog klijenta (paket hr.infodom.str.integration.egop);
 * property imena su zadržana identična.
 */
@Configuration
@ConditionalOnProperty(name = "hr.infodom.str.integration.egop.enabled", havingValue = "true")
public class EgopConfig {

    private final EgopProperties properties;

    public EgopConfig(EgopProperties properties) {
        // Integracija je uključena — nepotpuna konfiguracija mora pasti ovdje, glasno,
        // a ne tiho otići u eGOP kao prazan korisnik.
        properties.requireComplete();
        this.properties = properties;
    }

    @Bean
    public HttpComponentsMessageSender egopSender() {
        // NTCredentials NE parsira "DOMENA\korisnik" iz username stringa — domena se mora
        // predati zasebnim argumentom, inače NTLM Type-3 ide s praznom domenom i IIS
        // odbija prijavu. Podržavamo oba oblika: "DOMENA\korisnik" i goli "korisnik".
        String egopUsername = properties.username();
        String ntlmUser = egopUsername;
        String ntlmDomain = null;
        int sep = egopUsername.indexOf('\\');
        if (sep > 0) {
            ntlmDomain = egopUsername.substring(0, sep);
            ntlmUser = egopUsername.substring(sep + 1);
        }
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new NTCredentials(ntlmUser, properties.password(), null, ntlmDomain)
        );

        // IIS/NTLM: Content-Length postavlja transport, dupli header ruši zahtjev
        HttpRequestInterceptor interceptor = (request, context) ->
                request.removeHeaders(HttpHeaders.CONTENT_LENGTH);

        HttpComponentsMessageSender messageSender = new HttpComponentsMessageSender();
        messageSender.setHttpClient(HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setTargetPreferredAuthSchemes(List.of(AuthSchemes.NTLM))
                        .setConnectTimeout(properties.connectTimeoutMs())
                        // referentni klijent nema read timeout — bez njega obješeni eGOP
                        // blokira registracijski thread beskonačno
                        .setSocketTimeout(properties.readTimeoutMs())
                        .build())
                .setDefaultAuthSchemeRegistry(RegistryBuilder.<AuthSchemeProvider>create()
                        .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
                        .build())
                .setDefaultCredentialsProvider(credentialsProvider)
                .addInterceptorFirst(interceptor)
                .build());
        return messageSender;
    }

    @Bean
    SaajSoapMessageFactory soap12MessageFactory() {
        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.setSoapVersion(SoapVersion.SOAP_12);
        messageFactory.afterPropertiesSet();
        return messageFactory;
    }

    @Bean
    public WebServiceTemplate egopMDMWebServiceTemplate(SaajSoapMessageFactory soap12MessageFactory,
                                                        HttpComponentsMessageSender egopSender) {
        return buildTemplate(soap12MessageFactory, egopSender, "hr.infodom.egov.mdm",
                properties.mdm().url());
    }

    @Bean
    public WebServiceTemplate egopPismenoWebServiceTemplate(SaajSoapMessageFactory soap12MessageFactory,
                                                            HttpComponentsMessageSender egopSender) {
        return buildTemplate(soap12MessageFactory, egopSender, "hr.infodom.egov.pismeno",
                properties.pismeno().url());
    }

    @Bean
    public WebServiceTemplate egopPredmetWebServiceTemplate(SaajSoapMessageFactory soap12MessageFactory,
                                                            HttpComponentsMessageSender egopSender) {
        return buildTemplate(soap12MessageFactory, egopSender, "hr.infodom.egov.predmet",
                properties.predmet().url());
    }

    @Bean
    public WebServiceTemplate egopSubjektWebServiceTemplate(SaajSoapMessageFactory soap12MessageFactory,
                                                            HttpComponentsMessageSender egopSender) {
        return buildTemplate(soap12MessageFactory, egopSender, "hr.infodom.egov.subjekt",
                properties.subjekt().url());
    }

    private WebServiceTemplate buildTemplate(SaajSoapMessageFactory messageFactory,
                                             HttpComponentsMessageSender sender,
                                             String contextPath,
                                             String defaultUri) {
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath(contextPath);
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setMessageSender(sender);
        template.setDefaultUri(defaultUri);
        return template;
    }
}
