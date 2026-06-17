package com.str.backend.prefill;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class PrefillOpenApiConfig {

    @Bean
    OpenAPI prefillOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("STR — Registration Number Prefill API")
                        .version("1.0.0")
                        .description("""
                                Vanjski handoff endpoint za zahtjev za registracijskim brojem (RB).
                                Vanjski portali (TuStart i sl.) prosljeđuju autenticirane podatke najmoprimca
                                koji frontend STR-a koristi za prefill forme."""))
                .servers(List.of(
                        new Server().url("https://str.eturizam.gov.hr")
                                .description("Production (TBD — točan URL nije potvrđen; trenutni eTurizam: https://eturizam.gov.hr/)"),
                        new Server().url("https://str.testeduturizam.gov.hr")
                                .description("Test (CDU)"),
                        new Server().url("https://s-str-02.infodom.hr:8080")
                                .description("Dev (interni Infodom)"),
                        new Server().url("https://strbackend-production.up.railway.app")
                                .description("Mock (Railway)")
                ));
    }
}
