package com.str.backend.captcha;

public class CaptchaException extends RuntimeException {
    public CaptchaException(String messageKey) {
        super(messageKey);
    }
}
