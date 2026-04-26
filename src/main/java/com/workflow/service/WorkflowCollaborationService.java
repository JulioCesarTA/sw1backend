package com.workflow.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowCollaborationService {

    private final Map<String, Map<String, StageLock>> workflowLocks = new ConcurrentHashMap<>();
    private final Map<String, Set<SessionStageRef>> sessionLocks = new ConcurrentHashMap<>();

    public synchronized List<StageLock> getLocks(String workflowId) {
        return new ArrayList<>(workflowLocks.getOrDefault(workflowId, Map.of()).values());
    }

    public synchronized LockAttemptResult lockStage(String workflowId, String stageId, String sessionId, String userId, String userName) {
        Map<String, StageLock> locks = workflowLocks.computeIfAbsent(workflowId, ignored -> new ConcurrentHashMap<>());
        StageLock current = locks.get(stageId);

        if (current == null) {
            StageLock created = new StageLock(workflowId, stageId, sessionId, userId, userName, Instant.now());
            locks.put(stageId, created);
            sessionLocks.computeIfAbsent(sessionId, ignored -> new HashSet<>()).add(new SessionStageRef(workflowId, stageId));
            return new LockAttemptResult(true, created, null);
        }

        if (Objects.equals(current.getSessionId(), sessionId) || Objects.equals(current.getUserId(), userId)) {
            return new LockAttemptResult(true, current, null);
        }

        return new LockAttemptResult(false, null, current);
    }

    public synchronized StageLock unlockStage(String workflowId, String stageId, String sessionId, String userId) {
        Map<String, StageLock> locks = workflowLocks.get(workflowId);
        if (locks == null) return null;

        StageLock current = locks.get(stageId);
        if (current == null) return null;
        if (!Objects.equals(current.getSessionId(), sessionId) && !Objects.equals(current.getUserId(), userId)) {
            return null;
        }

        locks.remove(stageId);
        if (locks.isEmpty()) workflowLocks.remove(workflowId);

        Set<SessionStageRef> refs = sessionLocks.get(sessionId);
        if (refs != null) {
            refs.remove(new SessionStageRef(workflowId, stageId));
            if (refs.isEmpty()) sessionLocks.remove(sessionId);
        }

        return current;
    }

    public synchronized boolean canMoveStage(String workflowId, String stageId, String sessionId, String userId) {
        Map<String, StageLock> locks = workflowLocks.get(workflowId);
        if (locks == null) return true;  // no locks at all → allow
        StageLock current = locks.get(stageId);
        if (current == null) return true; // stage not locked → allow
        // locked by someone else → deny; locked by me → allow
        return Objects.equals(current.getSessionId(), sessionId) || Objects.equals(current.getUserId(), userId);
    }

    public synchronized List<StageLock> releaseSession(String sessionId) {
        Set<SessionStageRef> refs = sessionLocks.remove(sessionId);
        if (refs == null || refs.isEmpty()) return List.of();

        List<StageLock> released = new ArrayList<>();
        for (SessionStageRef ref : refs) {
            Map<String, StageLock> locks = workflowLocks.get(ref.workflowId());
            if (locks == null) continue;
            StageLock current = locks.get(ref.stageId());
            if (current == null || !Objects.equals(current.getSessionId(), sessionId)) continue;
            locks.remove(ref.stageId());
            released.add(current);
            if (locks.isEmpty()) workflowLocks.remove(ref.workflowId());
        }
        return released;
    }

    @Getter
    @RequiredArgsConstructor
    public static class LockAttemptResult {
        private final boolean granted;
        private final StageLock lock;
        private final StageLock existingLock;
    }

    @Getter
    @RequiredArgsConstructor
    public static class StageLock {
        private final String workflowId;
        private final String stageId;
        private final String sessionId;
        private final String userId;
        private final String userName;
        private final Instant lockedAt;
    }

    private record SessionStageRef(String workflowId, String stageId) {}
}
