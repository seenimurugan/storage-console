package com.nila.storageconsole.security;

import com.nila.storageconsole.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${storage-console.jwt.secret}") String secretB64,
                      @Value("${storage-console.jwt.ttl-hours}") long ttlHours) {
        byte[] decoded = Base64.getDecoder().decode(secretB64);
        this.key = Keys.hmacShaKeyFor(decoded);
        this.ttlMillis = ttlHours * 60 * 60 * 1000L;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("name", user.getDisplayName())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getTtlMillis() { return ttlMillis; }
}
