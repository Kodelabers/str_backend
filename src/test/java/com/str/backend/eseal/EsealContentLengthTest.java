package com.str.backend.eseal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servis bez {@code Content-Length} vraća 411, a chunked tijelo ne prima. {@code MockRestServiceServer}
 * zaobilazi request factory, pa se to provjerava stvarnim HTTP serverom iz JDK-a, kroz isti builder
 * kao u produkciji.
 */
class EsealContentLengthTest {

    private HttpServer server;
    private final AtomicReference<String> contentLength = new AtomicReference<>();
    private final AtomicReference<String> transferEncoding = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(EsealHttpClient.PATH, exchange -> {
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            // Pečat dodaje bajtove; servis koji vrati nepromijenjen ulaz klijent odbija.
            String ulaz = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            byte[] body = (ulaz + " + pecat").getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.getResponseHeaders().add("Document-Guid", "3f2b8c1e-0d4a-4e57-9a61-2c7d5b9e8f10");
            exchange.getResponseHeaders().add("Control-Number", "7K3M-Q9RT-X2VW");
            exchange.getResponseHeaders().add("Seal-Level", "B-LT");
            exchange.getResponseHeaders().add("Repr-Digest", "sha-256=:" + sha256(body) + ":");
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsFixedContentLength_notChunked() {
        String base = "http://localhost:" + server.getAddress().getPort();
        EsealHttpClient client = new EsealHttpClient(EsealConfig.configure(RestClient.builder(),
                new EsealProperties(true, base, "kljuc", "STR", 2_000, 5_000)).build());
        byte[] pdf = "%PDF-1.4 akt".getBytes();

        client.seal(pdf, "0b8f6a7e-2f1c-4c55-9d1e-7a3b2c1d0e9f", null);

        assertThat(contentLength.get()).isEqualTo(String.valueOf(pdf.length));
        assertThat(transferEncoding.get()).isNull();
    }

    private static String sha256(byte[] body) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
