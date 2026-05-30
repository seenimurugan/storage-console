package com.nila.storageconsole.config;

import com.nila.storageconsole.user.User;
import com.nila.storageconsole.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminBootstrap {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    @Bean
    public ApplicationRunner ensureAdmin(UserRepository users,
                                         PasswordEncoder encoder,
                                         @Value("${storage-console.admin.username}") String username,
                                         @Value("${storage-console.admin.password}") String password,
                                         @Value("${storage-console.admin.display-name}") String displayName) {
        return args -> {
            boolean hasAdmin = users.findAll().stream().anyMatch(u -> u.getRole() == User.Role.ADMIN);
            if (hasAdmin) {
                log.info("Admin user already present — skipping bootstrap.");
                return;
            }
            User admin = new User();
            admin.setUsername(username);
            admin.setPasswordHash(encoder.encode(password));
            admin.setDisplayName(displayName);
            admin.setRole(User.Role.ADMIN);
            users.save(admin);
            log.info("Bootstrapped admin user '{}'. Change the password immediately if using the default.", username);
        };
    }
}
