package com.cdcplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CdcPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(CdcPlatformApplication.class, args);
    }
}
