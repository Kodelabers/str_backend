package com.str.backend.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Prosljeđuje korisnikovu Spring sesiju servisu koji dijeli istu tablicu sesija
 * ({@code session.spring_session}, str-internal-api).
 *
 * <p>Taj servis stoji iza NIAS-a i anonimni poziv dobiva 302 na prijavu. Sesija NIAS korisnika je
 * u zajedničkoj tablici, pa je dovoljno poslati njezin cookie u istom obliku kao preglednik:
 * {@code SESSION=<base64(id)>} (Spring Session {@code DefaultCookieSerializer}). Preglednik to sam
 * ne može, jer je {@code gov.hr} javni sufiks i cookie ne prelazi s domene na domenu.
 *
 * <p>Bez sesije, ili izvan HTTP zahtjeva, cookie se ne šalje — nova prazna sesija ne bi ništa
 * autorizirala. Interceptor se veže samo na klijente prema tom servisu: cookie je pristup
 * korisnikovoj sesiji i ne smije otići nikome drugome. Vrijednost se nikad ne logira.
 */
public class SharedSessionCookieInterceptor implements ClientHttpRequestInterceptor {

    static final String COOKIE_NAME = "SESSION";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        HttpSession session = currentSession();
        if (session != null) {
            String value = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
            request.getHeaders().add(HttpHeaders.COOKIE, COOKIE_NAME + "=" + value);
        }
        return execution.execute(request, body);
    }

    private static HttpSession currentSession() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest().getSession(false) : null;
    }
}
