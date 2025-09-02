package com.xiaoxin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfiguration{

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("后端模板项目")
                        .version("1.0")
                        .description("后端模板项目的接口文档"));
    }
    @Bean
    public GroupedOpenApi authAPI() {
        return GroupedOpenApi.builder()
                             .group("功能管理")
                             .pathsToMatch("/user/**","/post/**","/interfaceInfo/**")
                             .build();
    }
}