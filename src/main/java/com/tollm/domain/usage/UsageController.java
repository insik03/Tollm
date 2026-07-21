package com.tollm.domain.usage;

import com.tollm.domain.usage.dto.RequestLogResponse;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    // 내 사용량/비용 조회. userId는 JwtAuthFilter가 검증 후 request attribute에 넣어준 값
    @GetMapping("/me")
    public UsageSummaryResponse myUsage(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return usageService.myUsage(userId, from, to);
    }

    // 내 최근 요청 로그 (페이징). 예: /usage/me/logs?page=0&size=20&sort=createdAt,desc
    @GetMapping("/me/logs")
    public Page<RequestLogResponse> myLogs(@RequestAttribute("userId") Long userId, Pageable pageable) {
        return usageService.myLogs(userId, pageable);
    }
}
