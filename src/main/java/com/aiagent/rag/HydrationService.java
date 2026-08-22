package com.aiagent.rag;

import com.aiagent.model.Document;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class HydrationService {

    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;

    private final Map<Set<Long>, CompletableFuture<Map<Long, Document>>> flightCache = new ConcurrentHashMap<>();

    public List<org.springframework.ai.document.Document> hydrateAndValidate(
            List<org.springframework.ai.document.Document> chunks, User user) {

        if (chunks == null || chunks.isEmpty())
            return Collections.emptyList();

        Set<Long> docIds = chunks.stream()
                .map(this::getDocId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Document> dbMetadata = fetchBatch(docIds);
        final Map<Long, Document> snapshot = Map.copyOf(dbMetadata);

        List<ValidatedChunk> results = chunks.stream()
                .map(chunk -> validateChunk(chunk, snapshot, user))
                .collect(Collectors.toList());

        logMetrics(results);

        return results.stream()
                .filter(res -> res.status() == Status.VALID
                        || (res.status() != Status.ORPHAN && isSafeFallback(res.chunk())))
                .map(ValidatedChunk::chunk)
                .collect(Collectors.toList());
    }

    public enum Status {
        VALID, STALE, ORPHAN
    }

    public record ValidatedChunk(org.springframework.ai.document.Document chunk, Status status) {
    }

    private ValidatedChunk validateChunk(org.springframework.ai.document.Document chunk, Map<Long, Document> snapshot,
            User user) {
        Long id = getDocId(chunk);
        if (id == null)
            return new ValidatedChunk(chunk, Status.ORPHAN);

        Document meta = snapshot.get(id);

        if (meta == null) {
            log.warn("[HYDRATION] Orphan vector detected: doc_id={}", id);
            return new ValidatedChunk(chunk, Status.ORPHAN);
        }

        if (meta.isDeleted()) {
            return new ValidatedChunk(chunk, Status.ORPHAN);
        }

        int vectorVersion = getVersion(chunk);
        boolean isStale = (vectorVersion != -1 && vectorVersion != meta.getVersion());
        if (isStale) {
            log.warn("[HYDRATION] Version drift for doc {}: Vector={}, DB={}", id, vectorVersion, meta.getVersion());
        }

        // Also the approval-lifecycle defense-in-depth check: canAccessDocument
        // denies PENDING_APPROVAL/REJECTED documents to anyone but the
        // DIRECTOR/uploader. Those documents should never reach Qdrant at all
        // (the gate is in DocumentService.uploadDocument/approveDocument), so
        // this only matters if that gate is ever bypassed by a future bug —
        // but since it's live DB state re-checked on every query, it closes
        // that gap for free without a separate status check here.
        if (!documentAccessService.canAccessDocument(user, meta)) {
            log.warn("[HYDRATION] Access denied for doc {} for user {}", id, user.getEmail());
            return new ValidatedChunk(chunk, Status.ORPHAN);
        }

        updateMetadata(chunk, meta);
        return new ValidatedChunk(chunk, isStale ? Status.STALE : Status.VALID);
    }

    private void logMetrics(List<ValidatedChunk> results) {
        long total = results.size();
        if (total == 0)
            return;

        long orphans = results.stream().filter(r -> r.status() == Status.ORPHAN).count();
        long stale = results.stream().filter(r -> r.status() == Status.STALE).count();
        double dropRate = (double) orphans / total;

        log.info("[RAG-METRICS] Hydration complete. Total={}, Orphans={}, Stale={}, DropRate={}%",
                total, orphans, stale, String.format("%.2f", dropRate * 100));
    }

    private Map<Long, com.aiagent.model.Document> fetchBatch(Set<Long> docIds) {
        return flightCache.computeIfAbsent(docIds, ids -> CompletableFuture.supplyAsync(() -> {
            try {
                return documentRepository.findAllByIdInWithAssociations(ids).stream()
                        .collect(Collectors.toMap(com.aiagent.model.Document::getId, d -> d));
            } catch (Exception e) {
                log.error("[HYDRATION] Failed to fetch documents for ids: {}. Error: {}", ids, e.getMessage());
                return Collections.<Long, com.aiagent.model.Document>emptyMap();
            }
        }).orTimeout(3000, TimeUnit.MILLISECONDS)
                .thenApply(res -> res)
                .whenComplete((res, ex) -> flightCache.remove(docIds))).join();
    }

    private boolean isSafeFallback(org.springframework.ai.document.Document p) {
        if (!"PUBLIC".equals(p.getMetadata().get("access_level")))
            return false;
        Object indexedAt = p.getMetadata().getOrDefault("ingested_at", p.getMetadata().get("indexed_at"));
        long ts = -1;
        if (indexedAt instanceof Number n)
            ts = n.longValue();
        else if (indexedAt instanceof String s) {
            try {
                ts = Long.parseLong(s);
            } catch (Exception e) {
            }
        }
        return ts != -1 && (System.currentTimeMillis() - ts) < 86400000L;
    }

    private void updateMetadata(org.springframework.ai.document.Document chunk, Document meta) {
        Map<String, Object> m = chunk.getMetadata();
        m.put("document_id", String.valueOf(meta.getId()));
        m.put("document_uuid", meta.getDocumentUuid() != null ? meta.getDocumentUuid() : "");
        m.put("document_name", meta.getTitle());
        m.put("source", meta.getTitle());
        m.put("upload_date", meta.getCreatedAt().toString());
        m.put("decision_number", meta.getDecisionNumber() != null ? meta.getDecisionNumber()
                : (meta.getDecision() != null ? meta.getDecision() : "N/A"));
        m.put("user_name", meta.getUploaderName());
        m.put("uploader_role", meta.getUploaderRole() != null ? meta.getUploaderRole() : "UNKNOWN");
        m.put("department", meta.getDepartmentName() != null ? meta.getDepartmentName()
                : (meta.getDepartments().isEmpty() ? "N/A" : meta.getDepartments().iterator().next().getName()));
        m.put("project_name", meta.getProjectName() != null ? meta.getProjectName() : "N/A");
        m.put("internal_source_flag", String.valueOf(meta.isInternalSourceFlag()));
    }

    private Long getDocId(org.springframework.ai.document.Document p) {
        Object id = p.getMetadata().get("document_id");
        if (id instanceof Number n)
            return n.longValue();
        if (id instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("[HYDRATION] Failed to parse document_id '{}' as Long. Skipping chunk.", s);
            }
        }
        return null;
    }

    private int getVersion(org.springframework.ai.document.Document p) {
        Object v = p.getMetadata().get("version");
        if (v instanceof Number n)
            return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
