package com.xiaoxin.api.platform;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.xiaoxin.api.platform.mapper")
@EnableDubbo
public class PlatformApplication{

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }

}
