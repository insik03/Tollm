package com.tollm.domain.admin;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class AdminController {

    // TODO [2주차] 전체 사용량 통계 (ADMIN 권한 검증 필요)
    @GetMapping("/usage")
    public void allUsage() { throw new UnsupportedOperationException("TODO"); }

    // TODO [2주차] 사용자 쿼터 설정
    @PatchMapping("/quota/{userId}")
    public void setQuota(@PathVariable Long userId) { throw new UnsupportedOperationException("TODO"); }

    // TODO [확장] 일별 사용 추이, 사용자별 비용 랭킹, 캐시 히트율
    @GetMapping("/stats/daily")
    public void dailyStats() { throw new UnsupportedOperationException("TODO"); }
}
