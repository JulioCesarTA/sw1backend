package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "workflows")
public class Workflow {

    @Id
    private String id;

    private String name;

    private String description;

    private String companyId;

    @CreatedDate
    private Instant createdAt;

}
