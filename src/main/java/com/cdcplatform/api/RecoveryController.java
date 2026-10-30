package com.cdcplatform.api;
import com.cdcplatform.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.*;
@RestController
@RequestMapping("/api/tasks/{taskId}/recoveries")
public class RecoveryController{
 private final RecoveryService service;public RecoveryController(RecoveryService service){this.service=service;}
 @PostMapping("/precheck")Object precheck(@PathVariable long taskId,@RequestBody Map<String,Object>body,Authentication auth){return service.precheck(taskId,body,(ConsoleIdentityService.User)auth.getPrincipal());}
 @PostMapping Object create(@PathVariable long taskId,@RequestBody Map<String,Object>body,Authentication auth){return Map.of("id",service.create(taskId,body,(ConsoleIdentityService.User)auth.getPrincipal()));}
 @GetMapping Object list(@PathVariable long taskId){return service.list(taskId);}
 @GetMapping("/{id}")Object get(@PathVariable long taskId,@PathVariable String id){return service.get(taskId,id);}
 @PostMapping("/{id}/actions/{action}")void action(@PathVariable long taskId,@PathVariable String id,@PathVariable String action){service.action(taskId,id,action);}
}
