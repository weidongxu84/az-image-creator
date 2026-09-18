package io.weidongxu.webapp.imagecreator;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigTests {

    @Test
    void ignoresAuthBypassOutsideLocalMode() {
        org.assertj.core.api.Assertions.assertThat(AppConfig.localSkipAuth(false, "true"))
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(AppConfig.localSkipAuth(true, "true"))
                .isTrue();
    }

    @Test
    void createsNoLocalUserWhenAuthenticationIsSkipped() {
        AppConfig appConfig = mock(AppConfig.class);
        when(appConfig.isLocalSkipAuth()).thenReturn(true);
        SecurityConfig securityConfig = new SecurityConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(
                securityConfig, "appConfig", appConfig);

        UserDetailsService users =
                securityConfig.userDetailsService(new BCryptPasswordEncoder());

        assertThatThrownBy(() -> users.loadUserByUsername("local-user"))
                .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
    }
}
