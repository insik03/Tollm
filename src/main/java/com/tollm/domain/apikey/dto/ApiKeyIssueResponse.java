package com.tollm.domain.apikey.dto;

// key(원문)는 발급 응답에서 딱 한 번만 내려간다. 서버는 해시만 저장하므로 재조회 불가
public record ApiKeyIssueResponse(Long id, String key, String prefix) {
}
