package com.workflow.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FcmService {

    public void sendNotification(String fcmToken, String title, String body) {
        if (fcmToken == null || fcmToken.isBlank()) return;
        if (FirebaseApp.getApps().isEmpty()) {
            log.debug("Firebase no inicializado, notificación omitida.");
            return;
        }
        try {
            Message message = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .build();
            String response = FirebaseMessaging.getInstance().send(message);
            log.debug("Notificación FCM enviada: {}", response);
        } catch (Exception e) {
            log.error("Error enviando notificación FCM: {}", e.getMessage());
        }
    }
}
