package com.workflow.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowCollaborationService {

    private final Map<String, Map<String, NodoLock>> workflowLocks = new ConcurrentHashMap<>();
    private final Map<String, Set<SessionNodoRef>> sessionLocks = new ConcurrentHashMap<>();

    public synchronized List<NodoLock> getLocks(String workflowId) {
        return new ArrayList<>(workflowLocks.getOrDefault(workflowId, Map.of()).values());
    }

    public synchronized LockAttemptResult lockNodo(String workflowId, String nodoId, String sessionId, String userId, String userName) {
        Map<String, NodoLock> locks = workflowLocks.computeIfAbsent(workflowId, ignored -> new ConcurrentHashMap<>());
        NodoLock current = locks.get(nodoId);

        if (current == null) {
            NodoLock created = new NodoLock(workflowId, nodoId, sessionId, userId, userName, Instant.now());
            locks.put(nodoId, created);
            sessionLocks.computeIfAbsent(sessionId, ignored -> new HashSet<>()).add(new SessionNodoRef(workflowId, nodoId));
            return new LockAttemptResult(true, created, null);
        }

        if (Objects.equals(current.getSessionId(), sessionId) || Objects.equals(current.getUserId(), userId)) {
            return new LockAttemptResult(true, current, null);
        }

        return new LockAttemptResult(false, null, current);
    }

    public synchronized NodoLock unlockNodo(String workflowId, String nodoId, String sessionId, String userId) {
        Map<String, NodoLock> locks = workflowLocks.get(workflowId);
        if (locks == null) return null;

        NodoLock current = locks.get(nodoId);
        if (current == null) return null;
        if (!Objects.equals(current.getSessionId(), sessionId) && !Objects.equals(current.getUserId(), userId)) {
            return null;
        }

        locks.remove(nodoId);
        if (locks.isEmpty()) workflowLocks.remove(workflowId);

        Set<SessionNodoRef> refs = sessionLocks.get(sessionId);
        if (refs != null) {
            refs.remove(new SessionNodoRef(workflowId, nodoId));
            if (refs.isEmpty()) sessionLocks.remove(sessionId);
        }

        return current;
    }

    public synchronized boolean canMoveNodo(String workflowId, String nodoId, String sessionId, String userId) {
        Map<String, NodoLock> locks = workflowLocks.get(workflowId);
        if (locks == null) return true;  // no locks at all → allow
        NodoLock current = locks.get(nodoId);
        if (current == null) return true; // nodo not locked → allow
        // locked by someone else → deny; locked by me → allow
        return Objects.equals(current.getSessionId(), sessionId) || Objects.equals(current.getUserId(), userId);
    }

    public synchronized List<NodoLock> releaseSession(String sessionId) {
        Set<SessionNodoRef> refs = sessionLocks.remove(sessionId);
        if (refs == null || refs.isEmpty()) return List.of();

        List<NodoLock> released = new ArrayList<>();
        for (SessionNodoRef ref : refs) {
            Map<String, NodoLock> locks = workflowLocks.get(ref.workflowId());
            if (locks == null) continue;
            NodoLock current = locks.get(ref.nodoId());
            if (current == null || !Objects.equals(current.getSessionId(), sessionId)) continue;
            locks.remove(ref.nodoId());
            released.add(current);
            if (locks.isEmpty()) workflowLocks.remove(ref.workflowId());
        }
        return released;
    }

    @Getter
    @RequiredArgsConstructor
    public static class LockAttemptResult {
        private final boolean granted;
        private final NodoLock lock;
        private final NodoLock existingLock;
    }

    @Getter
    @RequiredArgsConstructor
    public static class NodoLock {
        private final String workflowId;
        private final String nodoId;
        private final String sessionId;
        private final String userId;
        private final String userName;
        private final Instant lockedAt;
    }

    private record SessionNodoRef(String workflowId, String nodoId) {}
}
