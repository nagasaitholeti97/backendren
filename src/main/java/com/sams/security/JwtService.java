package com.sams.security;

import com.sams.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(@Value("${sams.jwt.secret}") String secret,
                      @Value("${sams.jwt.ttl-minutes:120}") long ttlMinutes) {
        // HS256 needs at least 32 bytes.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    public String issue(String userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId)
            .claim("role", role.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlMinutes * 60)))
            .signWith(key)
            .compact();
    }

    /** Short-lived token that can only be used to reset a password (never accepted as a login token). */
    public String issueReset(String userId, String passwordVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId)
            .claim("purpose", "reset")
            .claim("pv", passwordVersion)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(30 * 60)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
