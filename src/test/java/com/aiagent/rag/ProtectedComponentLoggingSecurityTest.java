package com.aiagent.rag;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiagent.model.User;
import com.aiagent.rag.analyzer.QueryAnalyzer;
import com.aiagent.rag.filter.MetadataFilterBuilder;
import com.aiagent.rag.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-007 (approved follow-up) — the four log statements that previously
 * printed raw document content / raw user queries inside protected RAG
 * components (RagService, VectorStoreService, HybridRetrievalService,
 * QueryAnalyzer) must now only log length/id metadata. This test changes
 * ONLY assertions about logging output; it does not exercise or assert
 * anything about retrieval order, filters, Top-K, or authorization —
 * those remain fully owned by the existing RAG test suite
 * (SecurityIsolationTest, RagIntegrationTest, etc.).
 */
class ProtectedComponentLoggingSecurityTest {

    private static final String CONFIDENTIAL_MARKER = "SALARY-TABLE-CONFIDENTIAL-9f3a";

    private ListAppender<ILoggingEvent> attach(Class<?> loggerClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerClass)).detachAppender(appender);
    }

    private List<String> formattedMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void ragService_finalContextLog_neverContainsDocumentContent() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        HydrationService hydrationService = mock(HydrationService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ProvenanceBuilder provenanceBuilder = mock(ProvenanceBuilder.class);
        ChatModel chatModel = mock(ChatModel.class);

        RagService ragService = new RagService(vectorStoreService, hybridRetrievalService,
                hydrationService, promptBuilder, provenanceBuilder, chatModel);

        Document confidentialDoc = new Document(
                "Danh sách lương nhân viên: " + CONFIDENTIAL_MARKER + " - 50 ký tự trở lên để vượt ngưỡng chất lượng.",
                Map.of("document_id", "101"));
        List<Document> docs = List.of(confidentialDoc);

        User user = new User();
        user.setId(1L);
        user.setEmail("user@company.com");

        when(hybridRetrievalService.search(anyString(), any(), any())).thenReturn(docs);
        when(hydrationService.hydrateAndValidate(any(), any())).thenReturn(docs);
        when(promptBuilder.buildPrompt(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new org.springframework.ai.chat.prompt.Prompt("prompt"));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new org.springframework.ai.chat.model.Generation("safe answer"))));
        when(provenanceBuilder.buildProvenanceData(any())).thenReturn(Collections.emptyList());

        ListAppender<ILoggingEvent> appender = attach(RagService.class);
        try {
            ragService.processQuery("query", user, mock(Filter.Expression.class), Collections.emptyList(), "");

            List<String> messages = formattedMessages(appender);
            assertFalse(messages.stream().anyMatch(m -> m.contains(CONFIDENTIAL_MARKER)),
                    "RAG-CONTEXT log must never contain retrieved document content");
            assertTrue(messages.stream().anyMatch(m -> m.contains("[RAG-CONTEXT]") && m.contains("length")),
                    "a safe length-only diagnostic should still be logged");
        } finally {
            detach(RagService.class, appender);
        }
    }

    @Test
    void vectorStoreService_logs_neverContainQueryOrDocumentContent() {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorStoreService service = new VectorStoreService(vectorStore);
        ReflectionTestUtils.setField(service, "defaultThreshold", 0.3);
        ReflectionTestUtils.setField(service, "topK", 10);

        String sensitiveQuery = "mật khẩu admin " + CONFIDENTIAL_MARKER;
        Document doc = new Document(CONFIDENTIAL_MARKER + " nội dung tài liệu mật", Map.of("document_id", "101"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        ListAppender<ILoggingEvent> appender = attach(VectorStoreService.class);
        try {
            service.search(sensitiveQuery, mock(Filter.Expression.class));

            List<String> messages = formattedMessages(appender);
            assertFalse(messages.stream().anyMatch(m -> m.contains(CONFIDENTIAL_MARKER)),
                    "VectorStoreService logs must never contain the raw query or document content");
            assertTrue(messages.stream().anyMatch(m -> m.contains("QueryLength")),
                    "a safe query-length diagnostic should still be logged");
            assertTrue(messages.stream().anyMatch(m -> m.contains("ContentLength")),
                    "a safe content-length diagnostic should still be logged");
        } finally {
            detach(VectorStoreService.class, appender);
        }
    }

    @Test
    void hybridRetrievalService_chunkPreviewLog_neverContainsDocumentContent() {
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        MetadataFilterBuilder filterBuilder = mock(MetadataFilterBuilder.class);
        HybridRetrievalService service = new HybridRetrievalService(vectorStoreService, filterBuilder);

        Document doc = new Document(CONFIDENTIAL_MARKER + " nội dung chunk", Map.of("document_id", "101"));
        when(filterBuilder.build(any())).thenReturn(null);
        when(vectorStoreService.search(anyString(), any())).thenReturn(List.of(doc));

        ListAppender<ILoggingEvent> appender = attach(HybridRetrievalService.class);
        try {
            service.search("query", null, Collections.emptyList());

            List<String> messages = formattedMessages(appender);
            assertFalse(messages.stream().anyMatch(m -> m.contains(CONFIDENTIAL_MARKER)),
                    "HybridRetrievalService chunk-preview log must never contain document content");
            assertTrue(messages.stream().anyMatch(m -> m.contains("ContentLength")),
                    "a safe content-length diagnostic should still be logged");
        } finally {
            detach(HybridRetrievalService.class, appender);
        }
    }

    @Test
    void queryAnalyzer_extractionLog_neverContainsRawQuestion() {
        QueryAnalyzer analyzer = new QueryAnalyzer();

        String sensitiveQuestion = "Cho tôi biết " + CONFIDENTIAL_MARKER + " chi tiết dự án mật";

        ListAppender<ILoggingEvent> appender = attach(QueryAnalyzer.class);
        try {
            analyzer.extractCandidates(sensitiveQuestion);

            List<String> messages = formattedMessages(appender);
            // Scope: this locks in the approved fix to the "[QUERY-ANALYZER] Extracting
            // candidates from ..." line only. NOTE: the separate "[CANDIDATE] Detected
            // Candidates: ..." line can still echo fragments/the whole question when it
            // matches the titled-phrase pattern — that is a distinct, not-yet-approved
            // finding, intentionally out of scope for this fix (see P1.2 follow-up report).
            assertFalse(messages.stream().anyMatch(m -> m.contains("Extracting candidates from question") && m.contains(CONFIDENTIAL_MARKER)),
                    "the extraction-start log line specifically must not contain question content");
            assertTrue(messages.stream().anyMatch(m -> m.contains("[QUERY-ANALYZER]") && m.contains("length")),
                    "a safe length-only diagnostic should still be logged");
        } finally {
            detach(QueryAnalyzer.class, appender);
        }
    }
}
