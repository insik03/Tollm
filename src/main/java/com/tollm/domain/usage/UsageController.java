package com.tollm.domain.usage;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/usage")
public class UsageController {

    // TODO [2주차] 내 사용량/비용 조회 (기간 파라미터: from, to)
    @GetMapping("/me")
    public void myUsage() { throw new UnsupportedOperationException("TODO"); }

    // TODO [2주차] 내 최근 요청 로그 (페이징 - Pageable 사용)
    @GetMapping("/me/logs")
    public void myLogs() { throw new UnsupportedOperationException("TODO"); }
}
