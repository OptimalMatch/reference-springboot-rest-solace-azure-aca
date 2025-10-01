package com.example.solaceservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

@SpringBootApplication
@EnableJms
public class SolaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolaceServiceApplication.class, args);
    }
}