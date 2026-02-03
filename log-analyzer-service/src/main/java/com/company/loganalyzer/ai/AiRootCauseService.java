package com.company.loganalyzer.ai;

import com.company.loganalyzer.model.IncidentEntity;
import com.company.loganalyzer.model.LogDocument;
import com.company.loganalyzer.model.RootCauseAnalysis;
import com.company.loganalyzer.repository.IncidentRepository;
import com.company.loganalyzer.repository.LogRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Profile("!lite")
public class AiRootCauseService {

    private final ChatClient chatClient;
    private final IncidentRepository incidentRepository;
    private final LogRepository logRepository;

    public AiRootCauseService(ChatClient.Builder chatClientBuilder, IncidentRepository incidentRepository,
            LogRepository logRepository) {
        this.chatClient = chatClientBuilder.build();
        this.incidentRepository = incidentRepository;
        this.logRepository = logRepository;
    }

    public RootCauseAnalysis analyzeIncident(Long incidentId) {
        IncidentEntity incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new RuntimeException("Incident not found"));

        // Retrieve logs for the service
        // In a real scenario, would filter by time range [startTime - 5m, endTime]
        List<LogDocument> logs = logRepository.findByServiceName(incident.getServiceName());

        // Take top 50 recent errors
        String logContext = logs.stream()
                .filter(l -> "ERROR".equals(l.getLevel()))
                .limit(50)
                .map(l -> String.format("[%s] %s: %s", l.getTimestamp(), l.getLevel(), l.getMessage()))
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                You are a Senior Site Reliability Engineer.
                Analyze the following logs and incident details to determine the root cause.
                Output JSON matching this structure:
                {
                    "summary": "...",
                    "probableRootCause": "...",
                    "recommendedActions": ["action1", "action2"],
                    "confidenceScore": 0.95
                }
                """;

        String userPrompt = String.format("""
                Incident Type: %s
                Service: %s
                Description: %s

                Logs:
                %s
                """, incident.getType(), incident.getServiceName(), incident.getDescription(), logContext);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(RootCauseAnalysis.class);
    }
}
