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

    /**
     * 可配置的网关地址，优先级：配置 > 客户端默认
     */
    private String host;

    @Bean
    public XiaoxinApiClient xiaoxinApiClient(){
        if (host != null && !host.isBlank()){
            return new XiaoxinApiClient(accessKey, secretKey, host);
        }
        return new XiaoxinApiClient(accessKey, secretKey);
    }
}
