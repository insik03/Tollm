package com.tollm.domain.team.dto;

import jakarta.validation.constraints.Size;

// 팀 안에서 쓸 본인 닉네임 설정 요청. 빈 값이면 닉네임 해제(이메일로 표시).
public record SetNicknameRequest(@Size(max = 50, message = "닉네임은 50자 이하여야 합니다") String nickname) {
}
