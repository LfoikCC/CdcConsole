package com.cdcplatform.api;

import com.cdcplatform.service.ConsoleIdentityService;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import java.util.*;

@RestController
@RequestMapping("/api")
public class IdentityController {
    private final ConsoleIdentityService identities;
    public IdentityController(ConsoleIdentityService identities){this.identities=identities;}
    public static String cookie(HttpServletRequest request){if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals("CDC_SESSION"))return c.getValue();return null;}
    private void cookie(HttpServletRequest request,HttpServletResponse response,String id){response.addHeader("Set-Cookie",ResponseCookie.from("CDC_SESSION",id).httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/").build().toString());}
    @GetMapping("/auth/csrf") Object csrf(HttpServletRequest request,HttpServletResponse response){var s=identities.session(cookie(request));if(s==null){s=identities.newSession(null);cookie(request,response,s.id());}response.setHeader("Cache-Control","no-store");return Map.of("token",s.csrf());}
    @PostMapping("/auth/login") ResponseEntity<?> login(@RequestBody Map<String,String> body,HttpServletRequest request,HttpServletResponse response){
        String attempted=Objects.toString(body.get("username"),"").trim();
        request.setAttribute("audit-actor",attempted.substring(0,Math.min(80,attempted.length())));
        var u=identities.authenticate(body.get("username"),body.get("password"));
        if(u==null) return ResponseEntity.status(401).body(Map.of("error","用户名或密码不正确，或账号已停用"));
        identities.revoke(cookie(request));var session=identities.newSession(u.id());cookie(request,response,session.id());request.setAttribute("audit-user",u);return ResponseEntity.ok(u);
    }
    @GetMapping("/auth/me") Object me(Authentication auth,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return auth.getPrincipal();}
    @PostMapping("/auth/logout") void logout(HttpServletRequest req,HttpServletResponse res){identities.revoke(cookie(req));res.addHeader("Set-Cookie",ResponseCookie.from("CDC_SESSION","").httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/").maxAge(0).build().toString());}
    @PutMapping("/auth/password") void password(Authentication auth,@RequestBody Map<String,String> body){identities.changePassword(((ConsoleIdentityService.User)auth.getPrincipal()).id(),body.get("currentPassword"),body.getOrDefault("password",""));}
    @GetMapping("/users") Object users(){return identities.list();}
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) Object create(@RequestBody Map<String,Object> body){return Map.of("id",identities.create(body));}
    @PutMapping("/users/{id}") void update(@PathVariable long id,@RequestBody Map<String,Object> body){identities.update(id,body);}
    @DeleteMapping("/users/{id}") void delete(@PathVariable long id){identities.action(id,"delete",Map.of());}
    @PostMapping("/users/{id}/actions/{action}") void action(@PathVariable long id,@PathVariable String action,@RequestBody(required=false)Map<String,Object> body){identities.action(id,action,body==null?Map.of():body);}
}
