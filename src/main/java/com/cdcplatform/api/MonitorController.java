package com.cdcplatform.api;

import com.cdcplatform.service.MonitorService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    private final MonitorService monitor;
    public MonitorController(MonitorService monitor) { this.monitor=monitor; }
    @GetMapping
    Object get(@RequestParam(defaultValue="1h") String range,
               @RequestParam(defaultValue="false") boolean onlyRunning,
               @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return monitor.snapshot(range,onlyRunning,page,size);
    }
}
