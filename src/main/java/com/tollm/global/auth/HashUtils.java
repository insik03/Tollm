package com.tollm.global.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// API 키 해싱 전용 (SHA-256).
// 비밀번호는 bcrypt(PasswordEncoder), API 키는 SHA-256 — 근거는 README 기술 선정 근거 참고
public final class HashUtils {

    private HashUtils() {} // 유틸 클래스는 인스턴스 생성 금지

    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없는 JVM", e);
        }
    }
}
