package com.str.backend.email;

public interface EmailService {

    /**
     * Pristupni podaci non-EU iznajmljivaču odmah po registraciji. {@code password} je u
     * čitljivom obliku — implementacija ga ne smije logirati ni spremati.
     *
     * @return je li poruka predana SMTP poslužitelju; neuspjeh se ne ponavlja, jer bi red
     *         ponovnog slanja morao čuvati lozinku
     */
    boolean sendRegistrationNotification(String to, String firstName, String username, String password);

    void sendRejectionNotification(String to, String firstName);

    /**
     * Obavijest o izdanom registracijskom broju, svakom iznajmljivaču, s aktom u privitku. Non-EU
     * iznajmljivaču je to ujedno dostava; ostalima je privitak preslika, akt ide u korisnički pretinac.
     *
     * @return je li poruka predana SMTP poslužitelju. Pozivatelj po tome bilježi da je obavijest
     *         poslana — neuspjeh se ne smije zabilježiti kao poslan, inače ga retry preskače.
     */
    boolean sendRnIssuedNotification(RnIssuedMail mail);

    /**
     * Obavijest o promjeni statusa registracijskog broja (suspenzija, reaktivacija,
     * povlačenje, opoziv). Jedna metoda umjesto četiri jer se razlikuju samo predloškom;
     * {@link RnLifecycleMail#template()} bira tekst.
     *
     * @return ishod predaje SMTP poslužitelju; neuspjeh ide u {@link MailRetryStore}, a poruku
     *         odbijenu zbog veličine ({@link IshodSlanja#PREVELIKA}) pozivatelj odmah šalje bez
     *         privitaka priloga
     */
    IshodSlanja sendRnLifecycleNotification(RnLifecycleMail mail);
}
