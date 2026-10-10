package com.cdcplatform.api;
import com.cdcplatform.service.AlertMailService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/mail")
public class MailController {
    private final AlertMailService mail;
    public MailController(AlertMailService mail){this.mail=mail;}
    @GetMapping("/config") Object config(){return mail.config(false);}
    @PutMapping("/config") Object config(@RequestBody AlertMailService.Config c){return mail.save(c);}
    @PostMapping("/test-connection") Object test(@RequestBody(required=false) AlertMailService.Config config) throws Exception {mail.testConnection(config);return Map.of("success",true);}
    @PostMapping("/test") Object test(@RequestBody Map<String,String> body){mail.testMail(body.get("recipient"));return Map.of("status","QUEUED");}
    @GetMapping("/rules") Object rules(){return mail.rules();}
    @PostMapping("/rules") Object create(@RequestBody AlertMailService.Rule r){return mail.saveRule(null,r);}
    @PostMapping("/rules/{id}/test") Object testRule(@PathVariable long id){var rule=mail.rules().stream().filter(r->r.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("订阅规则不存在"));rule.recipients().forEach(mail::testMail);return Map.of("status","QUEUED");}
    @PutMapping("/rules/{id}") Object update(@PathVariable long id,@RequestBody AlertMailService.Rule r){return mail.saveRule(id,r);}
    @DeleteMapping("/rules/{id}") void delete(@PathVariable long id){mail.deleteRule(id);}
    @GetMapping("/history") Object history(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return mail.history(page,size);}
    @PostMapping("/history/{id}/retry") void retry(@PathVariable long id){mail.retry(id);}
}
