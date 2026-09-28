package com.str.backend.registration;

import java.util.UUID;

/**
 * Tko traži zahtjev (podnesak) — za provjeru vlasništva PDF-a.
 *
 * <ul>
 *   <li>non-EU iznajmljivač ({@code LessorPrincipal}) → {@code lessorId}</li>
 *   <li>NIAS korisnik → {@code oib} iz assertiona</li>
 *   <li>{@link #ANYONE} → bez provjere; samo kad NIAS nije uključen i nema prijave (local/mock
 *       razvoj, gdje je endpoint ionako {@code permitAll})</li>
 * </ul>
 */
public record SubmissionRequester(UUID lessorId, String oib, boolean unrestricted) {

    public static final SubmissionRequester ANYONE = new SubmissionRequester(null, null, true);

    public static SubmissionRequester lessor(UUID lessorId) {
        return new SubmissionRequester(lessorId, null, false);
    }

    public static SubmissionRequester oib(String oib) {
        return new SubmissionRequester(null, oib, false);
    }
}
