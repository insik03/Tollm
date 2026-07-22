package com.tollm.domain.team.dto;

import com.tollm.domain.team.TeamMember;

// GET /teams/mine - 대시보드의 "팀 선택" 드롭다운을 채우는 용도라 본인 role도 같이 내려준다
// (프론트에서 OWNER 전용 버튼 - 초대 링크 발급 등 - 노출 여부를 결정할 수 있게)
public record MyTeamResponse(Long teamId, String name, String role) {
    public static MyTeamResponse from(TeamMember member) {
        return new MyTeamResponse(member.getTeam().getId(), member.getTeam().getName(), member.getTeamRole().name());
    }
}
