package com.xiaoxin.api.platform.config;

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
                        .title("小新接口开发平台")
                        .version("1.0")
                        .description("小新接口开发平台的接口文档"));
    }
    @Bean
    public GroupedOpenApi authAPI() {
        return GroupedOpenApi.builder()
                             .group("功能管理")
                             .pathsToMatch("/user/**","/post/**","/interfaceInfo/**","/userInterfaceInfo/**")
                             .build();
    }
}