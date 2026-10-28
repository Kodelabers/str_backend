package com.str.backend.objection;

/** Sadržaj dokumenta prigovora za preuzimanje na detalju RB-a. */
public record SuspensionObjectionFileDto(String fileName, String contentType, byte[] content) {}
