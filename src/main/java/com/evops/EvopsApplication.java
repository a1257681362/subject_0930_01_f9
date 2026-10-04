package com.evops;

import org.apache.shiro.spring.boot.autoconfigure.ShiroAnnotationProcessorAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 本服务只用 Shiro 过滤器链（HTTP Basic），不使用 Shiro 注解；
// 排除注解自动配置以避免其 JDK 动态代理创建器与 @Transactional 的 CGLIB 代理冲突。
@SpringBootApplication(exclude = {ShiroAnnotationProcessorAutoConfiguration.class})
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}
