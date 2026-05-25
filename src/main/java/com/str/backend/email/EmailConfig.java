package com.str.backend.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class EmailConfig {

    @Bean
    @ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true", matchIfMissing = true)
    public EmailService smtpEmailService(JavaMailSender mailSender, MailProperties properties) {
        return new SmtpEmailService(mailSender, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false")
    public EmailService loggingEmailService() {
        return new LoggingEmailService();
    }
}
