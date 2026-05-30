package com.nila.storageconsole.security;

import com.nila.storageconsole.user.User;
import com.nila.storageconsole.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        User u = users.findByUsername(req.username().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!encoder.matches(req.password(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        String token = jwt.issue(u);
        return new LoginResponse(token, jwt.getTtlMillis() / 1000,
                new MeDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole().name()));
    }

    @GetMapping("/me")
    public MeDto me(@AuthenticationPrincipal AuthUser principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        User u = users.findById(principal.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole().name());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, long expiresInSeconds, MeDto user) {}
    public record MeDto(Long id, String username, String displayName, String role) {}
}
