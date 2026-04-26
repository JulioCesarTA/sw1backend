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

    public List<User> findAll() {
        return userRepo.findAll();
    }

    public User findOne(String id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    }

    public User create(Map<String, Object> body) {
        String email = (String) body.get("email");
        if (userRepo.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El email ya esta registrado");
        }
        User user = new User();
        user.setName((String) body.get("name"));
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode((String) body.get("password")));
        user.setRole(User.Role.valueOf(((String) body.getOrDefault("role", "ATENCION_CLIENTE")).toUpperCase()));
        user.setCompanyId((String) body.get("companyId"));
        user.setDepartmentId((String) body.get("departmentId"));
        user.setJobTitle((String) body.get("jobTitle"));
        return userRepo.save(user);
    }

    public User update(String id, Map<String, Object> body) {
        User user = findOne(id);
        if (body.containsKey("name")) user.setName((String) body.get("name"));
        if (body.containsKey("email")) user.setEmail((String) body.get("email"));
        if (body.containsKey("role")) user.setRole(User.Role.valueOf(((String) body.get("role")).toUpperCase()));
        if (body.containsKey("companyId")) user.setCompanyId((String) body.get("companyId"));
        if (body.containsKey("departmentId")) user.setDepartmentId((String) body.get("departmentId"));
        if (body.containsKey("jobTitle")) user.setJobTitle((String) body.get("jobTitle"));
        if (body.containsKey("password")) user.setPassword(passwordEncoder.encode((String) body.get("password")));
        return userRepo.save(user);
    }

    public void remove(String id) {
        findOne(id);
        userRepo.deleteById(id);
    }
}
