package com.str.backend.objection;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Sadržaj dokumenta prigovora za preuzimanje — na detalju RB-a i u „Dokumentima" RB-a (urudžbiran podnesak). */
public record SuspensionObjectionFileDto(String fileName, String contentType, byte[] content) {

    /**
     * Odgovor s dokumentom. PDF se otvara u pregledniku, Word se preuzima — preglednik ga ne prikazuje.
     * {@link ContentDisposition} RFC 5987-enkodira naziv (hrvatski znakovi, bez header injectiona).
     */
    public ResponseEntity<byte[]> toResponse() {
        ContentDisposition.Builder builder = SuspensionObjectionService.CONTENT_TYPE_PDF.equals(contentType)
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        builder.filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }
}
