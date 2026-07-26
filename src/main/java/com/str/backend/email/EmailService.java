package com.str.backend.email;

public interface EmailService {

    void sendApprovalNotification(String to, String firstName, String username);

    void sendRejectionNotification(String to, String firstName);

    /**
     * Non-EU lessor delivery channel for the issued registration number: PDF
     * is attached because non-EU lessors are not routed through eGOP.
     */
    void sendRnIssuedNotification(String to, String firstName, String registrationNumber, byte[] pdf);

    /**
     * Obavijest o promjeni statusa registracijskog broja (suspenzija, reaktivacija,
     * povlačenje, opoziv). Jedna metoda umjesto četiri jer se razlikuju samo predloškom;
     * {@link RnLifecycleMail#template()} bira tekst.
     */
    void sendRnLifecycleNotification(RnLifecycleMail mail);
}
