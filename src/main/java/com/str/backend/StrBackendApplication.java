package com.str.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StrBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(StrBackendApplication.class, args);
    }
}
