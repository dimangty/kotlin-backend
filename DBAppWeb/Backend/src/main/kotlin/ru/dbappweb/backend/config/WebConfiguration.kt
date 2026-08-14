package ru.dbappweb.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** CORS ограничен адресами локального webpack dev server и не открывает API произвольным сайтам. */
@Configuration
class WebConfiguration(
    @Value("\${app.allowed-origins:http://localhost:8081,http://127.0.0.1:8081}")
    allowedOrigins: String,
) : WebMvcConfigurer {
    private val origins = allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty).toTypedArray()

    /** Только два используемых REST-метода разрешены для учебного API. */
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*origins)
            .allowedMethods("GET", "POST")
            .allowedHeaders("Content-Type")
    }
}
