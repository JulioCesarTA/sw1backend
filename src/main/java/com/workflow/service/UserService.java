package com.workflow.service;

import com.workflow.model.User;
import com.workflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public List<User> findAll(User actor) {
        if (actor.getRole() == User.Role.SUPERADMIN) {
            return userRepo.findAll();
        }
        if (actor.getCompanyId() == null || actor.getCompanyId().isBlank()) {   
            return List.of();
        }
        return userRepo.findByCompanyIdOrderByNameAsc(actor.getCompanyId());
    }

    public User create(Map<String, Object> body, User actor) {
        String email = (String) body.get("email");
        if (userRepo.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El email ya esta registrado");
        }
        String companyId = actor.getRole() == User.Role.SUPERADMIN
                ? (String) body.get("companyId")
                : actor.getCompanyId();
        User user = new User();
        user.setName((String) body.get("name"));
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode((String) body.get("password")));
        user.setRole(User.Role.valueOf(((String) body.getOrDefault("role", "ADMIN")).toUpperCase()));
        user.setCompanyId(companyId);
        user.setDepartmentId((String) body.get("departmentId"));
        user.setJobRoleId((String) body.get("jobRoleId"));
        return userRepo.save(user);
    }

    public User update(String id, Map<String, Object> body, User actor) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        if (actor.getRole() != User.Role.SUPERADMIN) {
            if (actor.getCompanyId() == null || !actor.getCompanyId().equals(user.getCompanyId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este usuario");
            }
            body.put("companyId", actor.getCompanyId());
        }
        if (body.containsKey("name")) user.setName((String) body.get("name"));
        if (body.containsKey("email")) user.setEmail((String) body.get("email"));
        if (body.containsKey("role")) user.setRole(User.Role.valueOf(((String) body.get("role")).toUpperCase()));
        if (body.containsKey("companyId")) user.setCompanyId((String) body.get("companyId"));
        if (body.containsKey("departmentId")) user.setDepartmentId((String) body.get("departmentId"));
        if (body.containsKey("jobRoleId")) user.setJobRoleId((String) body.get("jobRoleId"));
        if (body.containsKey("password")) user.setPassword(passwordEncoder.encode((String) body.get("password")));
        return userRepo.save(user);
    }
}
