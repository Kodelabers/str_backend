package com.str.backend.email;

public interface EmailService {

    void sendApprovalNotification(String to, String firstName, String username);

    void sendRejectionNotification(String to, String firstName);

    /**
     * Non-EU lessor delivery channel for the issued registration number: PDF
     * is attached because non-EU lessors are not routed through eGOP.
     */
    void sendRnIssuedNotification(String to, String firstName, String registrationNumber, byte[] pdf);
}
