package com.company.loganalyzer.controller;

import com.company.loganalyzer.model.IncidentEntity;
import com.company.loganalyzer.model.LogDocument;
import com.company.loganalyzer.repository.IncidentRepository;
import com.company.loganalyzer.repository.LogRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.context.annotation.Profile;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:5180", "http://localhost:3000" })
@Profile("!lite")
public class ApiController {

        private final IncidentRepository incidentRepository;
        private final LogRepository logRepository;

        public ApiController(IncidentRepository incidentRepository, LogRepository logRepository) {
                this.incidentRepository = incidentRepository;
                this.logRepository = logRepository;
        }

        // ==================== INCIDENTS ====================

        @GetMapping("/incidents")
        public ResponseEntity<List<IncidentEntity>> getIncidents(
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) String serviceName,
                        @RequestParam(required = false) String type) {

                List<IncidentEntity> incidents = incidentRepository.findAll(Sort.by(Sort.Direction.DESC, "startTime"));

                // Apply filters
                if (status != null && !status.isEmpty()) {
                        incidents = incidents.stream()
                                        .filter(i -> i.getStatus().name().equalsIgnoreCase(status))
                                        .collect(Collectors.toList());
                }
                if (serviceName != null && !serviceName.isEmpty()) {
                        incidents = incidents.stream()
                                        .filter(i -> i.getServiceName().equals(serviceName))
                                        .collect(Collectors.toList());
                }
                if (type != null && !type.isEmpty()) {
                        incidents = incidents.stream()
                                        .filter(i -> i.getType().name().equalsIgnoreCase(type))
                                        .collect(Collectors.toList());
                }

                return ResponseEntity.ok(incidents);
        }

        @GetMapping("/incidents/{id}")
        public ResponseEntity<IncidentEntity> getIncident(@PathVariable Long id) {
                return incidentRepository.findById(id)
                                .map(ResponseEntity::ok)
                                .orElse(ResponseEntity.notFound().build());
        }

        @GetMapping("/incidents/stats")
        public ResponseEntity<Map<String, Object>> getIncidentStats() {
                List<IncidentEntity> all = incidentRepository.findAll();

                Map<String, Object> stats = new HashMap<>();
                stats.put("total", all.size());
                stats.put("open",
                                all.stream().filter(i -> i.getStatus() == IncidentEntity.IncidentStatus.OPEN).count());
                stats.put("resolved",
                                all.stream().filter(i -> i.getStatus() == IncidentEntity.IncidentStatus.RESOLVED)
                                                .count());

                // Count by type
                Map<String, Long> byType = all.stream()
                                .collect(Collectors.groupingBy(i -> i.getType().name(), Collectors.counting()));
                stats.put("byType", byType);

                // Count by service
                Map<String, Long> byService = all.stream()
                                .collect(Collectors.groupingBy(IncidentEntity::getServiceName, Collectors.counting()));
                stats.put("byService", byService);

                return ResponseEntity.ok(stats);
        }

        // ==================== LOGS ====================

        @GetMapping("/logs")
        public ResponseEntity<List<LogDocument>> getLogs(
                        @RequestParam(required = false) String level,
                        @RequestParam(required = false) String serviceName,
                        @RequestParam(required = false) String search,
                        @RequestParam(defaultValue = "100") int limit) {

                List<LogDocument> logs = StreamSupport.stream(logRepository.findAll().spliterator(), false)
                                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                                .collect(Collectors.toList());

                // Apply filters
                if (level != null && !level.isEmpty()) {
                        logs = logs.stream()
                                        .filter(l -> l.getLevel().equalsIgnoreCase(level))
                                        .collect(Collectors.toList());
                }
                if (serviceName != null && !serviceName.isEmpty()) {
                        logs = logs.stream()
                                        .filter(l -> l.getServiceName().equals(serviceName))
                                        .collect(Collectors.toList());
                }
                if (search != null && !search.isEmpty()) {
                        String searchLower = search.toLowerCase();
                        logs = logs.stream()
                                        .filter(l -> l.getMessage().toLowerCase().contains(searchLower))
                                        .collect(Collectors.toList());
                }

                // Apply limit
                logs = logs.stream().limit(limit).collect(Collectors.toList());

                return ResponseEntity.ok(logs);
        }

        @GetMapping("/logs/clusters")
        public ResponseEntity<List<Map<String, Object>>> getLogClusters() {
                List<LogDocument> logs = StreamSupport.stream(logRepository.findAll().spliterator(), false)
                                .filter(l -> l.getClusterId() != null && !l.getClusterId().isEmpty())
                                .collect(Collectors.toList());

                // Group by cluster ID
                Map<String, List<LogDocument>> clusters = logs.stream()
                                .collect(Collectors.groupingBy(LogDocument::getClusterId));

                List<Map<String, Object>> result = new ArrayList<>();
                for (Map.Entry<String, List<LogDocument>> entry : clusters.entrySet()) {
                        Map<String, Object> cluster = new HashMap<>();
                        cluster.put("clusterId", entry.getKey());
                        cluster.put("count", entry.getValue().size());
                        cluster.put("sample", entry.getValue().get(0));
                        cluster.put("services", entry.getValue().stream()
                                        .map(LogDocument::getServiceName)
                                        .distinct()
                                        .collect(Collectors.toList()));
                        result.add(cluster);
                }

                // Sort by count descending
                result.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));

                return ResponseEntity.ok(result);
        }

        @GetMapping("/logs/timeline")
        public ResponseEntity<List<Map<String, Object>>> getLogTimeline(
                        @RequestParam(defaultValue = "60") int minutes) {

                Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);

                List<LogDocument> logs = StreamSupport.stream(logRepository.findAll().spliterator(), false)
                                .filter(l -> l.getTimestamp().isAfter(cutoff))
                                .collect(Collectors.toList());

                // Group by minute
                Map<String, Map<String, Long>> timeline = new TreeMap<>();

                for (LogDocument log : logs) {
                        String minute = log.getTimestamp().truncatedTo(ChronoUnit.MINUTES).toString();
                        timeline.computeIfAbsent(minute, k -> new HashMap<>());
                        String level = log.getLevel();
                        timeline.get(minute).merge(level, 1L, Long::sum);
                }

                List<Map<String, Object>> result = new ArrayList<>();
                for (Map.Entry<String, Map<String, Long>> entry : timeline.entrySet()) {
                        Map<String, Object> point = new HashMap<>();
                        point.put("time", entry.getKey());
                        point.put("INFO", entry.getValue().getOrDefault("INFO", 0L));
                        point.put("WARN", entry.getValue().getOrDefault("WARN", 0L));
                        point.put("ERROR", entry.getValue().getOrDefault("ERROR", 0L));
                        result.add(point);
                }

                return ResponseEntity.ok(result);
        }

        // ==================== SERVICES ====================

        @GetMapping("/services")
        public ResponseEntity<List<String>> getServices() {
                List<LogDocument> logs = StreamSupport.stream(logRepository.findAll().spliterator(), false)
                                .collect(Collectors.toList());

                List<String> services = logs.stream()
                                .map(LogDocument::getServiceName)
                                .distinct()
                                .sorted()
                                .collect(Collectors.toList());

                return ResponseEntity.ok(services);
        }
}
