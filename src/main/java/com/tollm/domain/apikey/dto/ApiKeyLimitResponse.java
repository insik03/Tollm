package com.tollm.domain.apikey.dto;

// 활성 키 개수 상한 안내: 최대 몇 개까지 되는지, 지금 몇 개 쓰는지, 몇 개 남았는지.
// 대시보드에서 "발급하기 전에 얼마나 남았는지" 보여주기 위함.
public record ApiKeyLimitResponse(long maxActive, long active, long remaining) {
}
