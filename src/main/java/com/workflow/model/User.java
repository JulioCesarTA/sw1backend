package com.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@Document(collection = "users")
public class User {

    public enum Role {
        SUPERADMIN,
        ADMIN,
        CLIENTE,
        ATENCION_CLIENTE,
        VALIDADOR,
        TECNICO
    }

    @Id
    private String id;

    private String name;

    @Indexed(unique = true)
    private String email;

    private String password;

    private Role role;

    private String companyId;

    private String departmentId;

    private String jobRoleId;

    private String refreshTokenHash;

    private String fcmToken;
}
