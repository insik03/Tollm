package com.tollm.domain.team;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/teams")
public class TeamController {

    // TODO [확장] 팀 생성 (생성자는 OWNER로 등록)
    @PostMapping
    public void createTeam() { throw new UnsupportedOperationException("TODO"); }

    // TODO [확장] 팀원 초대 (OWNER만 가능)
    @PostMapping("/{teamId}/members")
    public void invite(@PathVariable Long teamId) { throw new UnsupportedOperationException("TODO"); }

    // TODO [확장] 팀별 사용량 통계 (JPQL 집계 - N+1 주의)
    @GetMapping("/{teamId}/usage")
    public void teamUsage(@PathVariable Long teamId) { throw new UnsupportedOperationException("TODO"); }
}
