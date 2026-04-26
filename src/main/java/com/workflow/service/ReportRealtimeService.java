package com.workflow.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class ReportRealtimeService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ReportService reportService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<ScheduledFuture<?>> pendingPublish = new AtomicReference<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if ("/topic/reports/dashboard".equals(destination)) {
            scheduleDashboardUpdate();
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void periodicDashboardUpdate() {
        scheduleDashboardUpdate();
    }

    public void scheduleDashboardUpdate() {
        ScheduledFuture<?> scheduled = scheduler.schedule(this::publishDashboardUpdate, 250, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pendingPublish.getAndSet(scheduled);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void publishDashboardUpdate() {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/reports/dashboard",
                    reportService.getDashboardStats()
            );
            messagingTemplate.convertAndSend(
                    "/topic/reports/by-workflow",
                    reportService.getProceduresByWorkflow()
            );
        } finally {
            pendingPublish.set(null);
        }
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }
}
