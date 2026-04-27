package com.workflow.auth.service;

import com.workflow.model.JobRole;
import com.workflow.model.User;
import com.workflow.repository.JobRoleRepository;
import com.workflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JobRoleRepository jobRoleRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public Map<String, Object> login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        user.setRefreshTokenHash(passwordEncoder.encode(refreshToken));
        userRepository.save(user);

        return Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "user", sanitize(user)
        );
    }

    public Map<String, String> refresh(String refreshToken) {
        if (!jwtService.isTokenValid(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido");
        }
        String userId = jwtService.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));

        if (user.getRefreshTokenHash() == null ||
                !passwordEncoder.matches(refreshToken, user.getRefreshTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido");
        }

        String newAccess = jwtService.generateAccessToken(user.getId(), user.getRole().name());
        return Map.of("accessToken", newAccess);
    }

    public Map<String, Object> register(String name, String email, String password) {
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El email ya está registrado");
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(User.Role.CLIENTE);
        User saved = userRepository.save(user);

        String accessToken = jwtService.generateAccessToken(saved.getId(), saved.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(saved.getId());
        saved.setRefreshTokenHash(passwordEncoder.encode(refreshToken));
        userRepository.save(saved);

        return Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "user", sanitize(saved)
        );
    }

    public void saveFcmToken(String userId, String fcmToken) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setFcmToken(fcmToken);
            userRepository.save(user);
        });
    }

    public void logout(String userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setRefreshTokenHash(null);
            userRepository.save(user);
        });
    }

    public Map<String, Object> me(User user) {
        return sanitize(user);
    }

    private Map<String, Object> sanitize(User user) {
        String jobRoleName = "";
        if (user.getJobRoleId() != null && !user.getJobRoleId().isBlank()) {
            jobRoleName = jobRoleRepository.findById(user.getJobRoleId())
                    .map(JobRole::getName).orElse("");
        }
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("name", user.getName());
        map.put("email", user.getEmail());
        map.put("role", user.getRole().name());
        map.put("companyId", user.getCompanyId() == null ? "" : user.getCompanyId());
        map.put("departmentId", user.getDepartmentId() == null ? "" : user.getDepartmentId());
        map.put("jobRoleId", user.getJobRoleId() == null ? "" : user.getJobRoleId());
        map.put("jobRoleName", jobRoleName);
        return map;
    }
}
