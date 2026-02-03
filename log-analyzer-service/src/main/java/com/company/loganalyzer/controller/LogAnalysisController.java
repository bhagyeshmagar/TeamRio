package com.company.loganalyzer.controller;

import com.company.loganalyzer.ai.AiRootCauseService;
import com.company.loganalyzer.model.RootCauseAnalysis;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;

@RestController
@RequestMapping("/api/incidents")
@Profile("!lite")
public class LogAnalysisController {

    private final AiRootCauseService aiRootCauseService;

    public LogAnalysisController(AiRootCauseService aiRootCauseService) {
        this.aiRootCauseService = aiRootCauseService;
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<RootCauseAnalysis> analyzeIncident(@PathVariable Long id) {
        try {
            RootCauseAnalysis analysis = aiRootCauseService.analyzeIncident(id);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            // If AI service fails (e.g. no key), return a fallback or error
            return ResponseEntity.internalServerError().build();
        }
    }
}
