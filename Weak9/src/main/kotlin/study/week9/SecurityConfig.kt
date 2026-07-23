package study.week9

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter

@Configuration
class SecurityConfig {
    @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)
    @Bean
    fun chain(http: HttpSecurity, tokenFilter: AccessTokenFilter): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .exceptionHandling {
            it.authenticationEntryPoint { _, response, _ -> response.sendError(HttpStatus.UNAUTHORIZED.value()) }
        }
        .authorizeHttpRequests { it.requestMatchers("/auth/**", "/health").permitAll().anyRequest().authenticated() }
        .addFilterBefore(tokenFilter, BasicAuthenticationFilter::class.java)
        .build()
}
