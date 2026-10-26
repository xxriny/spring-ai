package dev.springrag.controller;

import dev.springrag.dto.HealthDto;
import dev.springrag.dto.MeDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 상태와 현재 인증 사용자의 공개 정보를 제공한다. */
@RestController
@RequestMapping("/api")
@Tag(name = "System", description = "상태와 로그인 사용자 정보")
public class SystemController {
    /**
     * 서비스 상태를 반환한다. 인증 없이 호출할 수 있다.
     *
     * @return 서비스 상태
    */
    @Operation(summary = "상태 확인")
    @ApiResponse(responseCode = "200", description = "서비스가 응답함")
    @GetMapping("/health") public HealthDto health() { return new HealthDto("UP"); }

    /**
     * 인증된 사용자의 이름과 관리자 여부를 반환한다.
     *
     * @param auth 현재 인증 정보
     * @return 사용자 정보
    */
    @Operation(summary = "내 계정 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "인증 사용자 정보"),
        @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @SecurityRequirement(name = "basicAuth")
    @GetMapping("/me") public MeDto me(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return new MeDto(auth.getName(), admin);
    }
}
