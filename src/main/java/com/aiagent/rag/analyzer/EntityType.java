package com.aiagent.rag.analyzer;

public enum EntityType {
    PROJECT,
    DOCUMENT_TITLE,
    CUSTOMER,
    CONTRACT,
    INVOICE,
    EMPLOYEE,
    DEPARTMENT,
    TAG,
    KEYWORD,
    // Candidate that matched no known SQL table (project/department/document/user) —
    // used as a literal lookup value against row-level content ingested from
    // structured files (e.g. an XLSX employee code/name that only exists inside
    // an ingested row, not as a system entity). See MetadataVerificationService.
    ROW_VALUE
}
