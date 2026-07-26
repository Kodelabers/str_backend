package com.str.backend.document;

/**
 * Greška u predlošku ili u vezanju placeholdera. Podiže se na startu (učitavanje) ili pri
 * renderu; nikad se ne guta — akt s praznom sekcijom ili nezamijenjenom {@code ${...}}
 * oznakom je pravno neispravan dokument, a tiha rupa je gora od pada.
 */
public class DocumentTemplateException extends IllegalStateException {

    public DocumentTemplateException(String message) {
        super(message);
    }

    public DocumentTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
