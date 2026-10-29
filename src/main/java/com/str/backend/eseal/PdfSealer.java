package com.str.backend.eseal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Ulaz u pečatiranje za ostatak sustava. Postoji uvijek, i kad je eSeal ugašen, da pozivatelj
 * ({@code EgopFilingService}) ne mora znati diže li se HTTP klijent — pita {@link #isEnabled()}.
 *
 * <p>Uključenost se čita iz postojanja klijenta, a ne iz propertyja: {@code ESEAL_ENABLED=yes} Spring
 * veže kao {@code true}, a {@code @ConditionalOnProperty(havingValue = "true")} ga ne prihvaća — da
 * se odluka čita na dva mjesta, prvi bi akt pao na nepostojećem beanu.
 */
@Service
public class PdfSealer {

    private final ObjectProvider<EsealHttpClient> client;

    public PdfSealer(ObjectProvider<EsealHttpClient> client) {
        this.client = client;
    }

    public boolean isEnabled() {
        return client.getIfAvailable() != null;
    }

    /** @throws EsealException kad akt nije pečatiran */
    public EsealHttpClient.Sealed seal(byte[] pdf, String idempotencyKey, String businessReference) {
        return client.getObject().seal(pdf, idempotencyKey, businessReference);
    }
}
