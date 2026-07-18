package com.tollm.global.auth;

import com.tollm.domain.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-ms}") Long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }
    public String createToken(Long userId, Role role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }
    public Claims parse(String token) {
        return Jwts.parser()          // ① "토큰 검사기"를 만들기 시작
                .verifyWith(key)      // ② 검사할 때 쓸 서명 열쇠 지정 (createToken에서 서명한 그 key)
                .build()              // ③ 검사기 완성
                .parseSignedClaims(token)  // ④ 실제 검사 실행 — 서명이 위조됐거나 만료면 여기서 예외가 터짐
                .getPayload();        // ⑤ 검사 통과한 토큰의 내용물(Claims)만 꺼내서
    }
    public Long getUserId(String token) {
        Claims claims = parse(token);
        String subject = claims.getSubject();
        return Long.parseLong(subject);
    }
}
