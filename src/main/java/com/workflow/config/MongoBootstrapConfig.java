package com.workflow.config;

import com.workflow.model.User;
import com.workflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class MongoBootstrapConfig {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public ApplicationRunner seedSuperAdmin() {
        return args -> {
            String email = "julio@gmail.com";
            if (userRepository.existsByEmail(email)) return;
            User superAdmin = new User();
            superAdmin.setName("Julio");
            superAdmin.setEmail(email);
            superAdmin.setPassword(passwordEncoder.encode("julioavila"));
            superAdmin.setRole(User.Role.SUPERADMIN);
            userRepository.save(superAdmin);
        };
    }
}
