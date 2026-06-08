package com.legalassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.legalassistant.mapper")
public class LegalAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegalAssistantApplication.class, args);
    }
}
