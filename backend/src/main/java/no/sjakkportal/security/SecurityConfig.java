package no.sjakkportal.security;

import java.util.List;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.web.Problems;
import no.sjakkportal.config.SjakkportalProperties;
import no.sjakkportal.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(SjakkportalProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter, Problems problems,
                                            SjakkportalProperties properties)
            throws Exception {
        http
                // Stateless bearer-token API: there is no session to ride, so CSRF tokens add nothing.
                // The two cookie endpoints (refresh, logout) check the Origin header instead.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> { })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        // The API serves JSON only; nothing may be loaded from it or frame it.
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh",
                                "/api/auth/logout", "/api/auth/reset-password").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/ping").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/ws/**").permitAll()   // the WebSocket handshake authenticates itself
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> problems.write(ErrorCode.AUTH_REQUIRED, request, response))
                        .accessDeniedHandler((request, response, e) -> problems.write(
                                request.getUserPrincipal() == null ? ErrorCode.AUTH_REQUIRED : ErrorCode.AUTH_ACCESS_DENIED,
                                request, response)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // Spring's CORS filter answers a foreign origin with a bare 403; this turns it into a problem document.
                .addFilterBefore(new ForeignOriginFilter(properties.cors().allowedOrigins(), problems), CorsFilter.class);
        return http.build();
    }

    /** The JWT filter belongs to the security chain only; stop Boot from also registering it as a servlet filter. */
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SjakkportalProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        // Exact origins only: credentials are allowed, so a wildcard or pattern would be a hole.
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Trace-Id"));
        config.setExposedHeaders(List.of("X-Trace-Id", "Retry-After", "Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** Declared only to switch off Boot's generated in-memory user; authentication is JWT-based. */
    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("not used");
        };
    }

    @Bean
    RateLimiter rateLimiter() {
        return new RateLimiter();
    }
}
