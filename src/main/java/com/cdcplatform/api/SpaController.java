package com.cdcplatform.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/monitor", "/tasks", "/tasks/{id}", "/connections", "/connections/sources", "/connections/targets", "/alerts", "/settings"})
    public String frontend() { return "forward:/index.html"; }
}
