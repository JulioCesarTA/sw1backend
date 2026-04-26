package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@Document(collection = "departments")
@CompoundIndex(name = "company_name_unique", def = "{'companyId': 1, 'name': 1}", unique = true, sparse = true)
public class Department {

    @Id
    private String id;

    private String companyId;
    private String name;
}
