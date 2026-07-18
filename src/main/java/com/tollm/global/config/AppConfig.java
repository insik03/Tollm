package com.tollm.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {

    // 구현체(BCrypt)가 아닌 인터페이스(PasswordEncoder)로 주입받게 해서
    // 알고리즘 교체·테스트 모킹이 가능하게 한다 (DIP)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
