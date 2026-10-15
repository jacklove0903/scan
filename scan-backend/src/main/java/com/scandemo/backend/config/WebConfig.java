package com.scandemo.backend.config;

import com.scandemo.backend.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 内网工具：放开 /api/** 的跨域（含 X-Token 请求头），方便本机页面调试。
 * 除登录与健康检查外，所有 /api/** 都要求 X-Token 令牌。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE", "PUT", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录页/登录接口/健康检查/草稿页 HTML 本身不需要令牌（页面里再登录或跳转登录页）
                .excludePathPatterns("/api/auth/login", "/api/health", "/api/draft", "/api/login");
    }
}
