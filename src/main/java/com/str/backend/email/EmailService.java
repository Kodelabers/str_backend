package com.str.backend.email;

public interface EmailService {

    void sendApprovalNotification(String to, String firstName, String username);

    void sendRejectionNotification(String to, String firstName);

    /**
     * Obavijest o izdanom registracijskom broju, svakom iznajmljivaču. Non-EU iznajmljivaču je
     * to ujedno dostava (PDF u privitku); ostalima samo obavijest, akt ide u korisnički pretinac.
     *
     * @return je li poruka predana SMTP poslužitelju. Pozivatelj po tome bilježi da je obavijest
     *         poslana — neuspjeh se ne smije zabilježiti kao poslan, inače ga retry preskače.
     */
    boolean sendRnIssuedNotification(RnIssuedMail mail);

    /**
     * Obavijest o promjeni statusa registracijskog broja (suspenzija, reaktivacija,
     * povlačenje, opoziv). Jedna metoda umjesto četiri jer se razlikuju samo predloškom;
     * {@link RnLifecycleMail#template()} bira tekst.
     */
    void sendRnLifecycleNotification(RnLifecycleMail mail);
}
