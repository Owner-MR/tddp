package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenIntercepter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器1 拦截所有请求
        registry.addInterceptor(new RefreshTokenIntercepter(stringRedisTemplate)).order(0);
        //登录拦截器2，拦截部分请求
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
                "/user/login",
                "/user/code",
                "blog/hot",
                "shop/**",
                "shop-type/**",
                "upload/**",
                "voucher/**"
        ).order(1);

    }
}
