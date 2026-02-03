package com.company.loganalyzer.ingestion;

import com.company.loganalyzer.alerting.AlertService;
import com.company.loganalyzer.analysis.AnomalyDetector;
import com.company.loganalyzer.analysis.ErrorClusterer;
import com.company.loganalyzer.analysis.LogNormalizer;
import com.company.loganalyzer.config.KafkaConfig;
import com.company.loganalyzer.model.*;
import com.company.loganalyzer.repository.IncidentRepository;
import com.company.loganalyzer.repository.LogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Profile("!lite")
public class LogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionService.class);

    private final LogNormalizer logNormalizer;
    private final ErrorClusterer errorClusterer;
    private final AnomalyDetector anomalyDetector;
    private final LogRepository logRepository;
    private final IncidentRepository incidentRepository;
    private final AlertService alertService;

    public LogIngestionService(LogNormalizer logNormalizer, ErrorClusterer errorClusterer,
            AnomalyDetector anomalyDetector,
            LogRepository logRepository, IncidentRepository incidentRepository,
            AlertService alertService) {
        this.logNormalizer = logNormalizer;
        this.errorClusterer = errorClusterer;
        this.anomalyDetector = anomalyDetector;
        this.logRepository = logRepository;
        this.incidentRepository = incidentRepository;
        this.alertService = alertService;
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_APP_LOGS, groupId = "log-analyzer-group")
    @Transactional
    public void consumeLogs(LogEvent logEvent) {
        log.debug("Processing log: {}", logEvent);

        // 1. Normalize
        String normalizedMessage = logNormalizer.normalize(logEvent.message());

        // 2. Cluster
        String clusterId = errorClusterer.generateClusterId(normalizedMessage, logEvent.stackTrace());

        // 3. Persist Log to Elasticsearch
        LogDocument logDoc = new LogDocument(
                logEvent.serviceName(),
                logEvent.level(),
                logEvent.message(),
                normalizedMessage,
                clusterId,
                logEvent.timestamp() != null ? logEvent.timestamp() : Instant.now());
        logRepository.save(logDoc);

        // 4. Detect Anomalies
        List<AnomalyType> anomalies = anomalyDetector.detectAnomalies(logEvent.serviceName(), logEvent.level());

        if (!anomalies.isEmpty()) {
            log.warn("ANOMALY DETECTED for service {}: {}", logEvent.serviceName(), anomalies);
            createOrUpdateIncident(logEvent.serviceName(), anomalies);
        } else {
            log.info("Log processed. Cluster: {}", clusterId);
        }
    }

    private void createOrUpdateIncident(String serviceName, List<AnomalyType> anomalies) {
        // Simple logic: check if there is an OPEN incident for this service
        Optional<IncidentEntity> openIncident = incidentRepository.findFirstByServiceNameAndStatusOrderByStartTimeDesc(
                serviceName, IncidentEntity.IncidentStatus.OPEN);

        if (openIncident.isPresent()) {
            // In a real system, we might update the incident or add events to it
            log.info("Existing open incident found for service {}. ID: {}", serviceName, openIncident.get().getId());
        } else {
            // Create new incident
            IncidentEntity incident = new IncidentEntity(
                    serviceName,
                    anomalies.get(0), // Primary anomaly type
                    Instant.now(),
                    "Detected anomalies: " + anomalies,
                    IncidentEntity.IncidentStatus.OPEN);
            incidentRepository.save(incident);
            log.info("Created new incident for service {}. ID: {}", serviceName, incident.getId());

            // Trigger Alert
            alertService.sendAlert(incident);
        }
    }
}
