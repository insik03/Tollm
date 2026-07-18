package com.tollm.global.error;

// 모든 에러 응답의 공통 형식 { "code": ..., "message": ... }
public record ErrorResponse(String code, String message) {
}
