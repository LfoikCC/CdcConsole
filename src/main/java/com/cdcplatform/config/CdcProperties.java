package com.cdcplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@ConfigurationProperties(prefix = "cdc")
public class CdcProperties {
    private final Cache cache = new Cache();
    private final Delivery delivery = new Delivery();
    private String timeZone = "Asia/Shanghai";
    public Cache getCache() { return cache; }
    public Delivery getDelivery() { return delivery; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public static class Cache {
        private Path directory = Path.of("data/cache");
        private long segmentSizeMb = 100;
        private int retentionDays = 7;
        public Path getDirectory() { return directory; }
        public void setDirectory(Path directory) { this.directory = directory; }
        public long getSegmentSizeMb() { return segmentSizeMb; }
        public void setSegmentSizeMb(long segmentSizeMb) { this.segmentSizeMb = segmentSizeMb; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }
    public static class Delivery {
        private long maxPendingBytes = 10L * 1024 * 1024 * 1024;
        private int maxAttempts = 6;
        private int streamLoadMaxBytes = 10 * 1024 * 1024;
        public long getMaxPendingBytes() { return maxPendingBytes; }
        public void setMaxPendingBytes(long value) { this.maxPendingBytes = value; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { this.maxAttempts = value; }
        public int getStreamLoadMaxBytes() { return streamLoadMaxBytes; }
        public void setStreamLoadMaxBytes(int value) { this.streamLoadMaxBytes = value; }
    }
}
