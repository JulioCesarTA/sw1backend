package com.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowAiProxyService {

    @Value("${app.ai.base-url}")
    private String aiBaseUrl;

    @Value("${app.ai.tf-base-url}")
    private String tfBaseUrl;

    private final ObjectMapper objectMapper;
    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(120))
            .readTimeout(Duration.ofSeconds(120))
            .build();
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

    public org.springframework.http.ResponseEntity<byte[]> reportGenerateRaw(Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(tfBaseUrl + "/nlp/report-generate")
                    .post(RequestBody.create(json, MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ResponseStatusException(resolveStatus(response.code()), "Error del servicio de IA");
                }
                byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                String contentType = response.header("Content-Type", "application/json");
                String contentDisposition = response.header("Content-Disposition", "inline");
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Type", contentType)
                        .header("Content-Disposition", contentDisposition)
                        .body(bytes);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con el servicio de IA: " + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error proxy del servicio de IA: " + e.getMessage());
        }
    }

    public Map<String, Object> fillForm(Map<String, Object> body) {
        return postTo(tfBaseUrl, "/nlp/fill-form", body);
    }

    public Map<String, Object> rankPriorityWorkflow(String workflowId) {
        return postTo(tfBaseUrl, "/nlp/rank-priority-real/" + workflowId, Map.of());
    }

    public Map<String, Object> detectAnomaliesWorkflow(String workflowId) {
        return postTo(tfBaseUrl, "/nlp/detect-anomalies/" + workflowId, Map.of());
    }

    public void reloadWorkflowsAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                postTo(tfBaseUrl, "/nlp/reload-workflows", Map.of());
            } catch (Exception ignored) {}
        });
    }

    public org.springframework.http.ResponseEntity<byte[]> reportDownload(Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(tfBaseUrl + "/nlp/download")
                    .post(RequestBody.create(json, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ResponseStatusException(resolveStatus(response.code()), "Error del servicio de IA");
                }
                byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                String contentType = response.header("Content-Type", "application/octet-stream");
                String contentDisposition = response.header("Content-Disposition", "attachment");
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Type", contentType)
                        .header("Content-Disposition", contentDisposition)
                        .body(bytes);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con el servicio de IA: " + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error proxy del servicio de IA: " + e.getMessage());
        }
    }

    public Map<String, Object> matchWithDocs(String text, List<MultipartFile> files) {
        try {
            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("text", text == null ? "" : text);
            if (files != null) {
                for (MultipartFile file : files) {
                    if (file == null || file.isEmpty()) continue;
                    String filename = file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename();
                    String mediaType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
                    bodyBuilder.addFormDataPart(
                            "files",
                            filename,
                            RequestBody.create(file.getBytes(), MediaType.parse(mediaType))
                    );
                }
            }

            Request request = new Request.Builder()
                    .url(tfBaseUrl + "/nlp/match-with-docs")
                    .post(bodyBuilder.build())
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ResponseStatusException(resolveStatus(response.code()), response.body() != null ? response.body().string() : "Error del servicio de IA");
                }
                String responseBody = response.body() != null ? response.body().string() : "{}";
                return objectMapper.readValue(responseBody, new TypeReference<>() {});
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con el servicio de IA: " + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error proxy del servicio de IA: " + e.getMessage());
        }
    }

    public Map<String, Object> workflowRouter(String prompt, String companyId, List<MultipartFile> files) {
        try {
            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("prompt", prompt == null ? "" : prompt);
            if (companyId != null && !companyId.isBlank()) {
                bodyBuilder.addFormDataPart("companyId", companyId);
            }
            if (files != null) {
                for (MultipartFile file : files) {
                    if (file == null || file.isEmpty()) continue;
                    String filename = file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename();
                    String mediaType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
                    bodyBuilder.addFormDataPart(
                            "files",
                            filename,
                            RequestBody.create(file.getBytes(), MediaType.parse(mediaType))
                    );
                }
            }

            Request request = new Request.Builder()
                    .url(aiBaseUrl + "/workflow-router")
                    .post(bodyBuilder.build())
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ResponseStatusException(resolveStatus(response.code()), response.body() != null ? response.body().string() : "Error del servicio de IA");
                }
                String responseBody = response.body() != null ? response.body().string() : "{}";
                return objectMapper.readValue(responseBody, new TypeReference<>() {});
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con el servicio de IA: " + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error proxy del servicio de IA: " + e.getMessage());
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        return postTo(aiBaseUrl, path, body);
    }

    private Map<String, Object> postTo(String baseUrl, String path, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + path))
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
