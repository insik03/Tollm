package com.tollm.domain.user;

import com.tollm.domain.usage.UsageQuota;
import com.tollm.domain.usage.UsageQuotaRepository;
import com.tollm.domain.user.dto.LoginRequest;
import com.tollm.domain.user.dto.SignupRequest;
import com.tollm.domain.user.dto.TokenResponse;
import com.tollm.global.auth.JwtProvider;
import com.tollm.global.error.ApiException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UsageQuotaRepository usageQuotaRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    private static final BigDecimal DEFAULT_MONTHLY_LIMIT = BigDecimal.valueOf(5);

    @Transactional
    public void signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw ApiException.conflict("이미 가입된 이메일입니다");
        }
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role(Role.MEMBER)
                .build();
        userRepository.save(user);

        usageQuotaRepository.save(UsageQuota.builder()
                .user(user)
                .monthlyCostLimit(DEFAULT_MONTHLY_LIMIT)
                .build());
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> ApiException.unauthorized("이메일 또는 비밀번호가 올바르지 않습니다"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw ApiException.unauthorized("이메일 또는 비밀번호가 올바르지 않습니다");
        }
        return new TokenResponse(jwtProvider.createToken(user.getId(), user.getRole()));
    }
}