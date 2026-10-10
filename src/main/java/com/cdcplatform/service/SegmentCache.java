package com.cdcplatform.service;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.ChangeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SegmentCache {
    private final CdcProperties properties;
    private final ObjectMapper json;
    private final ReentrantLock lock = new ReentrantLock();

    public SegmentCache(CdcProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }
    @PostConstruct void init() throws IOException { Files.createDirectories(properties.getCache().getDirectory()); }

    public Path append(ChangeEvent input) {
        var event = input.normalized();
        lock.lock();
        try {
            var file = activeSegment();
            Files.writeString(file, json.writeValueAsString(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return file;
        } catch (IOException e) { throw new IllegalStateException("Unable to persist CDC event", e); }
        finally { lock.unlock(); }
    }

    public StagedBatch appendBatch(long mappingId, long generation, String sourceStart, String sourceEnd,
                                   List<ChangeEvent> input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("delivery batch is empty");
        var events = input.stream().map(ChangeEvent::normalized).toList();
        try {
            var eventLines = new ArrayList<String>(events.size());
            var digest = MessageDigest.getInstance("SHA-256");
            long payloadBytes = 0;
            for (var event : events) {
                String line = json.writeValueAsString(event) + System.lineSeparator();
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                digest.update(bytes); payloadBytes += bytes.length; eventLines.add(line);
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            String identity = mappingId + "|" + generation + "|" + sourceStart + "|" + sourceEnd + "|" + checksum;
            String batchId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
            Path directory = properties.getCache().getDirectory().resolve("batches").resolve(String.valueOf(mappingId));
            Files.createDirectories(directory);
            Path ready = directory.resolve(batchId + ".ready");
            if (!Files.exists(ready)) {
                Path temp = directory.resolve(batchId + ".tmp");
                var header = new LinkedHashMap<String, Object>();
                header.put("type", "CDC_BATCH"); header.put("version", 1); header.put("batchId", batchId);
                header.put("mappingId", mappingId); header.put("generation", generation);
                header.put("sourceStart", sourceStart); header.put("sourceEnd", sourceEnd);
                header.put("eventCount", events.size()); header.put("checksum", checksum);
                try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    writeFully(channel, StandardCharsets.UTF_8.encode(json.writeValueAsString(header) + System.lineSeparator()));
                    for (String line : eventLines) writeFully(channel, StandardCharsets.UTF_8.encode(line));
                    channel.force(true);
                }
                try { Files.move(temp, ready, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, ready, StandardCopyOption.REPLACE_EXISTING); }
            }
            return new StagedBatch(batchId, ready, checksum, events.size(), payloadBytes, events);
        } catch (Exception e) { throw new IllegalStateException("Unable to persist CDC batch", e); }
    }

    public List<ChangeEvent> readBatch(Path path, String expectedChecksum) {
        try {
            var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) throw new IllegalStateException("CDC batch file is empty");
            var digest = MessageDigest.getInstance("SHA-256");
            var events = new ArrayList<ChangeEvent>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i) + System.lineSeparator();
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                events.add(json.readValue(lines.get(i), ChangeEvent.class));
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedChecksum))
                throw new IllegalStateException("CDC batch checksum mismatch");
            return events;
        } catch (Exception e) { throw new IllegalStateException("Unable to read CDC batch " + path, e); }
    }

    public long totalPendingBytes() {
        Path batches = properties.getCache().getDirectory().resolve("batches");
        if (!Files.exists(batches)) return 0;
        try (var files = Files.walk(batches)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".ready"))
                    .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public List<BatchDescriptor> readyBatches() {
        Path batches = properties.getCache().getDirectory().resolve("batches");
        if (!Files.exists(batches)) return List.of();
        try (var files = Files.walk(batches)) {
            var result = new ArrayList<BatchDescriptor>();
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".ready")).toList()) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String first = reader.readLine();
                    Map<String, Object> header = json.readValue(first, new TypeReference<>() {});
                    result.add(new BatchDescriptor(String.valueOf(header.get("batchId")),
                            Long.parseLong(String.valueOf(header.get("mappingId"))),
                            Long.parseLong(String.valueOf(header.getOrDefault("generation", 0))),
                            value(header.get("sourceStart")), value(header.get("sourceEnd")),
                            String.valueOf(header.get("checksum")),
                            Integer.parseInt(String.valueOf(header.get("eventCount"))), path, Files.size(path)));
                }
            }
            return result;
        } catch (Exception e) { throw new IllegalStateException("Unable to scan CDC batch files", e); }
    }

    private String value(Object value) { return value == null ? null : String.valueOf(value); }

    private void writeFully(FileChannel channel, java.nio.ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    public List<Path> segments() {
        try (var files = Files.list(properties.getCache().getDirectory())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".ndjson"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @Scheduled(cron = "0 15 2 * * *")
    public void purgeExpired() {
        var cutoff = Instant.now().minus(properties.getCache().getRetentionDays(), ChronoUnit.DAYS);
        for (var path : segments()) try {
            BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
            if (attr.lastModifiedTime().toInstant().isBefore(cutoff)) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }

    public long totalBytes() { return segments().stream().mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum(); }

    private Path activeSegment() throws IOException {
        var dir = properties.getCache().getDirectory();
        var current = segments().stream().reduce((a, b) -> b).orElse(null);
        long limit = properties.getCache().getSegmentSizeMb() * 1024L * 1024L;
        if (current == null || Files.size(current) >= limit) {
            current = dir.resolve("events-%d.ndjson".formatted(System.currentTimeMillis()));
        }
        return current;
    }

    public record StagedBatch(String batchId, Path path, String checksum, int eventCount,
                              long payloadBytes, List<ChangeEvent> events) {}
    public record BatchDescriptor(String batchId, long mappingId, long generation, String sourceStart,
                                  String sourceEnd, String checksum, int eventCount, Path path, long payloadBytes) {}
}
