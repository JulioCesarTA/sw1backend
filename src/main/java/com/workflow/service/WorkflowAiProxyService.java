package com.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowAiProxyService {

    @Value("${app.ai.base-url}")
    private String aiBaseUrl;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public Map<String, Object> diagramCommand(Map<String, Object> body) {
        return post("/diagram-command", body);
    }

    public Map<String, Object> diagramVoiceCommand(Map<String, Object> body) {
        return post("/diagram-voice-command", body);
    }

    public Map<String, Object> bottleneckAnalysis(Map<String, Object> body) {
        return post("/bottleneck-analysis", body);
    }

    public Map<String, Object> workySuggestions(Map<String, Object> body) {
        return post("/worky-suggestions", body);
    }

    public Map<String, Object> formVoiceFill(Map<String, Object> body) {
        return post("/form-voice-fill", body);
    }

    public Map<String, Object> formVoiceDesign(Map<String, Object> body) {
        return post("/form-voice-design", body);
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(aiBaseUrl + path))
                            .timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(resolveStatus(response.statusCode()), response.body());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con el servicio de IA: " + e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con el servicio de IA: " + e.getMessage());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error proxy del servicio de IA: " + e.getMessage());
        }
    }

    private HttpStatus resolveStatus(int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        return status != null ? status : HttpStatus.BAD_GATEWAY;
    }
}
