package com.cdcplatform.config;
import com.cdcplatform.service.*;
import com.cdcplatform.api.IdentityController;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Configuration
public class BackendSecurityConfiguration {
 @Bean SecurityFilterChain backendSecurity(HttpSecurity http,ConsoleIdentityService identities,AuditLogService audit)throws Exception{
  http.csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable())
   .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->error(res,401,"请先登录")).accessDeniedHandler((req,res,e)->error(res,403,"当前角色没有此操作权限")))
   .authorizeHttpRequests(c->c.dispatcherTypeMatchers(DispatcherType.ASYNC,DispatcherType.ERROR).permitAll()
    .requestMatchers("/api/auth/csrf","/api/auth/login").permitAll()
    .requestMatchers(req->req.getRequestURI().startsWith("/api/")&&adminOnly(req)).hasRole("ADMIN")
    .requestMatchers("/api/auth/password","/api/auth/logout").authenticated()
    .requestMatchers(req->req.getRequestURI().startsWith("/api/")&&(!req.getMethod().equals("GET")||req.getRequestURI().equals("/api/audit-logs/export"))).hasAnyRole("ADMIN","OPERATOR")
    .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
   .addFilterBefore(new OncePerRequestFilter(){
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
     if(!req.getRequestURI().startsWith("/api/")){chain.doFilter(req,res);return;}
     res.setHeader("Cache-Control","no-store");var session=identities.session(IdentityController.cookie(req));var user=identities.sessionUser(session);
     if(user!=null){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.role()))));req.setAttribute("audit-user",user);}
     req.setAttribute("request-id",UUID.randomUUID().toString());Object before=audit.before(req);
     try{
      if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())&&(session==null||!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8),Objects.toString(req.getHeader("X-CSRF-Token"),"").getBytes(StandardCharsets.UTF_8)))){error(res,user==null?401:403,"会话或安全令牌已失效，请刷新后重试");return;}
      chain.doFilter(req,res);
     }finally{audit.recordRequest(req,res,before);}
    }
   },UsernamePasswordAuthenticationFilter.class);
  return http.build();
 }
 private static boolean adminOnly(HttpServletRequest req){
  String p=req.getRequestURI(),m=req.getMethod();
  if(p.startsWith("/api/users")||p.startsWith("/api/mail")||p.equals("/api/settings"))return true;
  if(m.equals("GET"))return false;
  if(p.equals("/api/auth/password")||p.equals("/api/auth/logout")||p.equals("/api/auth/login"))return false;
  return !p.matches("/api/tasks/\\d+/actions/(start|pause|resume|stop)")&&!p.matches("/api/(sources|targets|accounts)/\\d+/probe")
   &&!p.matches("/api/alerts/\\d+/(acknowledge|resolve)")&&!p.matches("/api/tasks/\\d+/delivery-batches/[^/]+/retry")
   &&!p.matches("/api/tasks/\\d+/(subscriptions/\\d+/)?reconciliations")
   &&!p.matches("/api/tasks/\\d+/subscriptions/\\d+/actions/(pause|resume|retry-initialization)")
   &&!p.matches("/api/tasks/\\d+/checkpoints/diagnose")&&!p.matches("/api/tasks/\\d+/recoveries(/precheck|/[^/]+/actions/(pause|resume|retry))?")
   &&!p.matches("/api/subscriptions/\\d+/(actions/(pause|resume)|delivery-batches/[^/]+/retry)")
   // 数据开发：运行与预览对 OPERATOR 开放（与 /api/tasks 的启停一致），增删改仍限 ADMIN
   &&!p.matches("/api/flows/\\d+/runs")
   &&!p.matches("/api/flows/\\d+/runs/\\d+/cancel")
   &&!p.matches("/api/flows/\\d+/nodes/[^/]+/preview")
   &&!p.matches("/api/flows/validate");
 }
 private static void error(HttpServletResponse r,int status,String text)throws IOException{r.setStatus(status);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"error\":\""+text+"\"}");}
}
