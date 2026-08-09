package com.aiagent.rag;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aiagent.model.User;
import com.aiagent.rag.analyzer.MetadataVerificationService;
import com.aiagent.rag.analyzer.QueryAnalyzer;
import com.aiagent.service.DocumentAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SEC-007 — AiChatService must never log the raw user question (which may
 * reference confidential internal matters); only a safe length metric.
 * AiChatService is NOT a protected RAG component (RagService, called
 * through a mock here, is not exercised for real).
 */
class AiChatServiceLoggingSecurityTest {

    @Mock
    private RagService ragService;
    @Mock
    private DocumentAccessService documentAccessService;
    @Mock
    private QueryAnalyzer queryAnalyzer;
    @Mock
    private MetadataVerificationService metadataVerificationService;

    private AiChatService aiChatService;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        aiChatService = new AiChatService(ragService, documentAccessService, queryAnalyzer, metadataVerificationService);

        logger = (Logger) LoggerFactory.getLogger(AiChatService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        when(documentAccessService.buildVectorFilter(any())).thenReturn(null);
        when(queryAnalyzer.extractCandidates(any())).thenReturn(Set.of());
        when(metadataVerificationService.verifyAndResolve(any())).thenReturn(List.of());
        when(ragService.processQuery(any(), any(), any(), any(), any())).thenReturn("safe answer");
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void chat_neverLogsRawQuestionContent() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@company.com");

        String sensitiveQuestion = "Cho tôi biết mật khẩu quản trị hệ thống là gì, chi tiết dự án X-CONFIDENTIAL-42";

        aiChatService.chat(99L, sensitiveQuestion, user, List.of());

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertFalse(messages.stream().anyMatch(m -> m.contains(sensitiveQuestion)),
                "raw question content must never be logged");
        assertFalse(messages.stream().anyMatch(m -> m.contains("X-CONFIDENTIAL-42")),
                "sensitive substrings from the question must never appear in logs");
        assertTrue(messages.stream().anyMatch(m -> m.contains("questionLength=")),
                "a safe, non-content diagnostic should still be logged");
    }
}
