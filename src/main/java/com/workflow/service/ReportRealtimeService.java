package com.workflow.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ReportRealtimeService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ReportService reportService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingPublish;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if ("/topic/reports/dashboard".equals(destination)) {
            scheduleDashboardUpdate();
        }
    }

    public synchronized void scheduleDashboardUpdate() {
        if (pendingPublish != null) {
            pendingPublish.cancel(false);
        }
        pendingPublish = scheduler.schedule(this::publishDashboardUpdate, 250, TimeUnit.MILLISECONDS);
    }

    private void publishDashboardUpdate() {
        try {
            messagingTemplate.convertAndSend("/topic/reports/dashboard", reportService.getDashboardStats());
        } finally {
            pendingPublish = null;
        }
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }
}
