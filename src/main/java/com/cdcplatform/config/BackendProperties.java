package com.cdcplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "backend")
public class BackendProperties {
    private final Auth auth = new Auth();
    private String corsAllowedOrigins = "*";

    public Auth getAuth() { return auth; }
    public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(String value) { this.corsAllowedOrigins = value; }

    public static class Auth {
        private String mode = "NONE";
        private String issuer = "";
        private String audience = "";
        public String getMode() { return mode; }
        public void setMode(String value) { this.mode = value == null ? "NONE" : value.toUpperCase(); }
        public String getIssuer() { return issuer; }
        public void setIssuer(String value) { this.issuer = value == null ? "" : value; }
        public String getAudience() { return audience; }
        public void setAudience(String value) { this.audience = value == null ? "" : value; }
    }
}
