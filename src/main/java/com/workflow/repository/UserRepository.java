package com.workflow.repository;

import com.workflow.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByCompanyIdOrderByNameAsc(String companyId);
    List<User> findByRole(User.Role role);
    List<User> findByDepartmentId(String departmentId);
}
