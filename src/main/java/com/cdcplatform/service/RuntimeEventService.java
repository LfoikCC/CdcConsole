package com.cdcplatform.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class RuntimeEventService {
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ArrayDeque<RuntimeEvent> recent = new ArrayDeque<>();
    private long sequence;
    private static final int HISTORY_LIMIT = 256;

    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void logWritten(com.cdcplatform.repository.TaskRepository.TaskLogWritten event) {
        publish("task-log", Map.of("taskId", event.taskId()));
    }

    public synchronized SseEmitter subscribe(String lastEventId) {
        var emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        long cursor = parseCursor(lastEventId);
        boolean gap = cursor > 0 && (!recent.isEmpty() && cursor < recent.peekFirst().id() - 1 || cursor > sequence);
        try {
            var connected = SseEmitter.event().name("connected").data(Map.of("at", Instant.now(), "replayGap", gap));
            if (gap) connected.id(Long.toString(sequence));
            emitter.send(connected);
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
            emitter.complete();
            return emitter;
        }
        if (!gap && cursor > 0) {
            for (var event : new ArrayList<>(recent)) if (event.id() > cursor) send(emitter, event);
        }
        return emitter;
    }

    public synchronized void publish(String event, Object value) {
        var item = new RuntimeEvent(++sequence, event, value);
        if (!"heartbeat".equals(event)) {
            recent.addLast(item);
            while (recent.size() > HISTORY_LIMIT) recent.removeFirst();
        }
        emitters.forEach(emitter -> send(emitter, item));
    }

    @Scheduled(fixedDelay = 15000)
    void heartbeat() { publish("heartbeat", Map.of("at", Instant.now())); }

    private long parseCursor(String value) {
        try { return value == null ? 0 : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private void send(SseEmitter emitter, RuntimeEvent event) {
        try { emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.name()).data(event.value())); }
        catch (IOException | IllegalStateException e) { emitters.remove(emitter); emitter.complete(); }
    }

    private record RuntimeEvent(long id, String name, Object value) {}
}
