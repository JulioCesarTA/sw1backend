package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "collab_documents")
public class CollabDocument {

    @Id
    private String id;

    @Indexed
    private String tramiteId;

    private String companyId;
    private String title;

    private String textSnapshot;

    private String ydocState;

    /** storedName of the source file this doc was opened from (nullable) */
    @Indexed
    private String fileStoredName;

    /** workflowId used as S3 folder — stored so we can re-convert on demand */
    private String workflowId;

    /** Initial HTML imported from the source file */
    private String initialHtml;

    private String createdBy;
    private String createdByName;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
