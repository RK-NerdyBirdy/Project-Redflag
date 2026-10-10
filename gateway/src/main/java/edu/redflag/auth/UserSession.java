package edu.redflag.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class UserSession {
    // Demo key for local prototyping - in prod, use environment variable
    private static final String SECRET = "MySuperSecretKeyForRedflagProject1234567890=";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    public static String generateToken(User user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.email())
                .claim("role", user.role())
                .issuedAt(new Date(now))
                .expiration(new Date(now + 3600000)) // 1 hour
                .signWith(KEY)
                .compact();
    }

    public static String verifyToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
