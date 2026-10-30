package com.cdcplatform.api;
import com.cdcplatform.service.CheckpointService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/tasks/{taskId}/checkpoints")
public class CheckpointController {
 private final CheckpointService service;public CheckpointController(CheckpointService service){this.service=service;}
 @GetMapping Object current(@PathVariable long taskId){return service.current(taskId);}
 @GetMapping("/history")Object history(@PathVariable long taskId,@RequestParam(defaultValue="6h")String range){return service.history(taskId,range);}
 @PostMapping("/diagnose")Object diagnose(@PathVariable long taskId){return service.diagnose(taskId,true);}
}
