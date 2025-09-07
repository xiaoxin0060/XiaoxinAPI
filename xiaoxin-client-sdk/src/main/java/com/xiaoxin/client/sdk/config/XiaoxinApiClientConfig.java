package com.xiaoxin.client.sdk.config;

import com.xiaoxin.client.sdk.client.XiaoxinApiClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("xiaoxinapi.client")
@Data
@ComponentScan
public class XiaoxinApiClientConfig{
    private String accessKey;

    private String secretKey;

    @Bean
    public XiaoxinApiClient xiaoxinApiClient(){
        return new XiaoxinApiClient(accessKey, secretKey);
    }
}
