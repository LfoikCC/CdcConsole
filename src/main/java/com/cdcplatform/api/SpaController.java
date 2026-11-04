package com.cdcplatform.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/login", "/audit", "/monitor", "/tasks", "/tasks/{id}", "/tasks/{id}/edit", "/connections", "/connections/sources", "/connections/targets", "/alerts", "/settings",
            // 数据准备：直接访问或刷新都要能落到 SPA
            "/prep", "/prep/accounts", "/prep/datasets", "/prep/datasets/new", "/prep/datasets/{id}",
            // 数据开发：这些路径必须在这里登记，否则前端能跳过去、一刷新就是 404
            "/dev", "/dev/flows", "/dev/flows/new", "/dev/flows/{id}", "/dev/flows/{id}/edit", "/dev/flows/{id}/runs/{runId}",
            // 数据分析：同上，刷新必须能落回 SPA
            "/bi", "/bi/dashboards", "/bi/dashboards/{id}", "/bi/dashboards/{id}/edit"})
    public String frontend() { return "forward:/index.html"; }
}
