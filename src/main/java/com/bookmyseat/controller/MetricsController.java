package com.bookmyseat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Friendly alias: GET /metrics serves the same Prometheus text as /actuator/prometheus. */
@Controller
public class MetricsController {

    @GetMapping("/metrics")
    public String metrics() {
        return "forward:/actuator/prometheus";
    }
}
