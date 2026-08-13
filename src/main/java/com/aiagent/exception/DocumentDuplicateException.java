package com.aiagent.exception;

import com.aiagent.model.DocumentDuplicateType;
import lombok.Getter;

/**
 * Thrown by DocumentDuplicateDetectionService when an upload matches an
 * existing document at file, content, or semantic level. duplicateDocumentId
 * / duplicateDocumentName are null whenever the requesting uploader has no
 * access to the matched document (DocumentAccessService#canAccessDocument) —
 * the upload is still rejected, but the identity of the existing document is
 * never disclosed to a user who couldn't otherwise see it.
 */
@Getter
public class DocumentDuplicateException extends RuntimeException {

    private final DocumentDuplicateType duplicateType;
    private final Long duplicateDocumentId;
    private final String duplicateDocumentName;

    public DocumentDuplicateException(DocumentDuplicateType duplicateType, Long duplicateDocumentId,
                                       String duplicateDocumentName, String message) {
        super(message);
        this.duplicateType = duplicateType;
        this.duplicateDocumentId = duplicateDocumentId;
        this.duplicateDocumentName = duplicateDocumentName;
    }
}
