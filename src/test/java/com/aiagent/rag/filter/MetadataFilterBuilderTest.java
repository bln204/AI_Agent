package com.aiagent.rag.filter;

import com.aiagent.rag.analyzer.DetectedEntity;
import com.aiagent.rag.analyzer.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataFilterBuilderTest {

    private final MetadataFilterBuilder builder = new MetadataFilterBuilder();

    @Test
    void rowValueEntity_buildsFilterOnRowValuesField() {
        Filter.Expression expr = builder.build(List.of(new DetectedEntity(EntityType.ROW_VALUE, "NV0003")));

        assertNotNull(expr);
        assertTrue(expr.toString().contains("row_values"), "filter must target the row_values metadata field");
        // Lower-cased to match XlsxChunker's case-insensitive row_values storage.
        assertTrue(expr.toString().contains("nv0003"));
    }

    @Test
    void rowValueEntity_withDiacritics_normalizesToMatchDiacriticFreeStoredData() {
        // Real spreadsheet data is often stored without Vietnamese diacritics,
        // but a user naturally types a question with them — the filter value
        // must be diacritics-stripped the same way XlsxChunker stores row_values.
        Filter.Expression expr = builder.build(List.of(new DetectedEntity(EntityType.ROW_VALUE, "Nguyễn Văn 11")));

        assertNotNull(expr);
        assertTrue(expr.toString().contains("nguyen van 11"), "filter was: " + expr);
    }

    @Test
    void employeeEntity_matchesBothUploaderNameAndRowContent() {
        // A candidate can verify as EMPLOYEE either because it's a real system
        // username, or because it coincidentally collides with one (e.g. DB
        // collation) while actually being a person's name inside an ingested
        // row (XlsxChunker's row_values) — the filter must not assume only
        // "user_name" (the document uploader) was meant.
        Filter.Expression expr = builder.build(List.of(new DetectedEntity(EntityType.EMPLOYEE, "Bùi Lê Nam")));

        assertNotNull(expr);
        assertTrue(expr.toString().contains("user_name"), "filter was: " + expr);
        assertTrue(expr.toString().contains("row_values"), "filter was: " + expr);
        // row_values side must be diacritics/case-normalized like XlsxChunker's storage.
        assertTrue(expr.toString().contains("bui le nam"), "filter was: " + expr);
        // user_name side must keep the original text (that field isn't normalized).
        assertTrue(expr.toString().contains("Bùi Lê Nam"), "filter was: " + expr);
    }

    @Test
    void noEntities_returnsNullFilter() {
        assertNull(builder.build(List.of()));
        assertNull(builder.build(null));
    }

    @Test
    void rowValueCombinedWithEmployee_isOred() {
        Filter.Expression expr = builder.build(List.of(
                new DetectedEntity(EntityType.EMPLOYEE, "director1"),
                new DetectedEntity(EntityType.ROW_VALUE, "NV0003")));

        assertNotNull(expr);
        assertTrue(expr.type() == Filter.ExpressionType.OR);
    }
}
