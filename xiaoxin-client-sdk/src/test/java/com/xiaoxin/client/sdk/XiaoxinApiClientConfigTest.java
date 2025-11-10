package com.xiaoxin.client.sdk;

import com.xiaoxin.client.sdk.client.XiaoxinApiClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = XiaoxinApiClientConfigTest.TestApp.class)
@TestPropertySource(properties = {
        "xiaoxinapi.client.access-key=test-ak",
        "xiaoxinapi.client.secret-key=test-sk",
        "xiaoxinapi.client.host=http://unit-test-host:9999"
})
public class XiaoxinApiClientConfigTest {

    @SpringBootApplication(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    static class TestApp {}

    @Autowired
    private XiaoxinApiClient client;

    @Test
    void should_bind_host_from_properties() {
        Assertions.assertNotNull(client);
        Assertions.assertEquals("http://unit-test-host:9999", client.getHost());
    }
}


