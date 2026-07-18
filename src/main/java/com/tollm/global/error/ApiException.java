package com.tollm.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// 비즈니스 예외는 전부 이 타입으로 던진다.
// 정적 팩토리로 상태코드-에러코드 조합을 한 곳에 고정 (호출부에서 조합 실수 방지)
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    // 레이트 리밋/쿼터 초과
    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message);
    }

    // 외부 LLM 프로바이더 장애
    public static ApiException badGateway(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", message);
    }
}
