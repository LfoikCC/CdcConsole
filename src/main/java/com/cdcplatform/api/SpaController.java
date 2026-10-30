package com.cdcplatform.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/login", "/audit", "/monitor", "/tasks", "/tasks/{id}", "/tasks/{id}/edit", "/connections", "/connections/sources", "/connections/targets", "/alerts", "/settings",
            // 数据开发：这些路径必须在这里登记，否则前端能跳过去、一刷新就是 404
            "/dev", "/dev/flows", "/dev/flows/new", "/dev/flows/{id}", "/dev/flows/{id}/edit", "/dev/flows/{id}/runs/{runId}"})
    public String frontend() { return "forward:/index.html"; }
}
