package com.cdcplatform.api;
import com.cdcplatform.service.AuditLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {
 private final AuditLogService audit;public AuditController(AuditLogService audit){this.audit=audit;}
 @GetMapping Object list(@RequestParam Map<String,String> p){return audit.page(p);}
 @GetMapping("/summary")Object summary(){return audit.summary();}
 @GetMapping("/{id}")Object detail(@PathVariable long id){return audit.detail(id);}
 @GetMapping("/export")void export(@RequestParam Map<String,String>p,HttpServletResponse res)throws java.io.IOException{res.setContentType("text/csv;charset=UTF-8");res.setHeader("Content-Disposition","attachment; filename=audit-log.csv");audit.export(p,res.getWriter());}
}
