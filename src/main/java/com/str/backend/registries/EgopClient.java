package com.str.backend.registries;

import java.time.Instant;

/**
 * STR functional spec §3 (core flow): eGOP — registry-office filing system.
 * Two calls during registration:
 *   1. {@link #reserveFilingNumber()} — returns the official filing number that
 *      gets stamped onto the request PDF.
 *   2. {@link #submitFiling(String, byte[])} — submits the final PDF (with the
 *      stamped number) to eGOP and confirms the filing record.
 */
public interface EgopClient {

    record FilingNumber(String classificationCode, String referenceNumber, Instant filedAt) {

        public String formatted() {
            return "KLASA: " + classificationCode + ", URBROJ: " + referenceNumber;
        }
    }

    record FilingConfirmation(String filingNumber, Instant confirmedAt) {}

    FilingNumber reserveFilingNumber();

    FilingConfirmation submitFiling(String filingNumber, byte[] pdf);
}
