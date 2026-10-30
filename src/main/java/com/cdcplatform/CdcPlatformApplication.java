package com.cdcplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class CdcPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(CdcPlatformApplication.class, args);
    }
}
