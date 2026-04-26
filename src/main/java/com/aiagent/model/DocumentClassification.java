package com.aiagent.model;

public enum DocumentClassification {
    INTERNAL_POLICY("Internal Policy"),
    PROJECT_DOCUMENT("Project Document"),
    DECISION_DOCUMENT("Decision Document"),
    REPORT("Report"),
    TECHNICAL_DOCUMENT("Technical Document"),
    OTHER("Other");

    private final String displayName;

    DocumentClassification(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
