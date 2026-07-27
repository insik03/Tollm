package com.tollm.global.auth;

import com.tollm.global.config.TollmProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

// [BYOK] 사용자가 등록한 프로바이더 키(OpenAI/Anthropic sk-...)를 DB에 "암호화해서" 저장하기 위한 서비스.
// 남의 시크릿을 보관하는 책임이 크므로 평문 저장은 절대 금지 - AES-256-GCM으로 암호화한다.
//
// - 키 유도: 설정값(BYOK_ENCRYPTION_KEY)을 SHA-256으로 해시해 32바이트 AES 키로 쓴다.
//   (설정값 길이/형식에 구애받지 않게 하려는 실용적 선택. 더 엄밀히 하려면 PBKDF2/Argon2 + salt를
//    쓰지만, 이 프로젝트 범위에선 고정 비밀 기반 대칭키로 충분하다고 판단)
// - GCM: 요청마다 랜덤 IV(12바이트)를 만들어 [IV || ciphertext+tag]를 base64로 저장한다.
//   GCM은 인증 태그가 붙어 있어 위·변조된 암호문은 복호화 단계에서 예외로 걸러진다.
@Component
public class CryptoService {

    private static final int IV_BYTES = 12;          // GCM 권장 IV 길이
    private static final int TAG_BITS = 128;         // GCM 인증 태그 길이
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(TollmProperties properties) {
        String secret = properties.getSecurity().getByokEncryptionKey();
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("키 암호화에 실패했습니다", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 태그 검증 실패(변조) 또는 잘못된 키 → 복호화 불가
            throw new IllegalStateException("키 복호화에 실패했습니다", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
