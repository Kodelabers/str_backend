package com.str.backend.categorization;

/** Sadržaj skeniranog rješenja za download na internom pregledu. */
public record CategorizationFileDto(String fileName, String contentType, byte[] content) {}
