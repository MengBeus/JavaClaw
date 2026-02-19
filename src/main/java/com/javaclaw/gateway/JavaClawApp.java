package com.javaclaw.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.javaclaw")
public class JavaClawApp {
    public static void main(String[] args) {
        SpringApplication.run(JavaClawApp.class, args);
    }
}
