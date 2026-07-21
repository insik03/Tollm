package com.tollm.domain.admin;

import com.tollm.domain.admin.dto.UpdateQuotaRequest;
import com.tollm.domain.usage.dto.AdminUsageSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // 권한 검증(ADMIN 전용)은 JwtAuthFilter가 /admin/** 진입 시점에 이미 처리하므로
    // 컨트롤러에서 role을 다시 확인하지 않는다 (AdminServiceTest/AdminControllerTest 참고)
    @GetMapping("/usage")
    public AdminUsageSummaryResponse allUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return adminService.allUsage(from, to);
    }

    @PatchMapping("/quota/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setQuota(@PathVariable Long userId, @Valid @RequestBody UpdateQuotaRequest request) {
        adminService.setQuota(userId, request.monthlyCostLimit());
    }

    // TODO [확장] 일별 사용 추이, 사용자별 비용 랭킹, 캐시 히트율 - non-goal, 이번 스코프에서 손대지 않음
    @GetMapping("/stats/daily")
    public void dailyStats() { throw new UnsupportedOperationException("TODO [확장]"); }
}
