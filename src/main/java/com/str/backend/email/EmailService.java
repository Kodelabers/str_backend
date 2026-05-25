package com.str.backend.email;

public interface EmailService {

    void sendApprovalNotification(String to, String firstName, String username);

    void sendRejectionNotification(String to, String firstName);
}
