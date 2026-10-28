package dev.springrag.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityConfigTest {
    private final SecurityConfig config = new SecurityConfig();

    @Test @DisplayName("GCP 프로필은 관리자 계정만 만들고 공유 user 계정을 만들지 않는다")
    void gcpHasOnlyAdmin() {
        var users = config.gcpUsers("secret");
        assertEquals("admin", users.loadUserByUsername("admin").getUsername());
        assertThrows(UsernameNotFoundException.class, () -> users.loadUserByUsername("user"));
    }

    @Test @DisplayName("로컬 프로필은 기존 관리자와 사용자 계정을 유지한다")
    void localKeepsBothUsers() {
        var users = config.localUsers("admin-secret", "user-secret");
        assertEquals("admin", users.loadUserByUsername("admin").getUsername());
        assertEquals("user", users.loadUserByUsername("user").getUsername());
    }
}
