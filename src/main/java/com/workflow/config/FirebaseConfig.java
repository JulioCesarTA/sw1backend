package com.workflow.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${app.firebase.service-account:}")
    private String serviceAccountJson;

    @PostConstruct
    public void initialize() {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("Firebase no configurado (FIREBASE_SERVICE_ACCOUNT vacío). Notificaciones push deshabilitadas.");
            return;
        }
        try {
            if (!FirebaseApp.getApps().isEmpty()) return;
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(
                            new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase Admin SDK inicializado correctamente.");
        } catch (Exception e) {
            log.error("Error inicializando Firebase: {}", e.getMessage());
        }
    }
}
