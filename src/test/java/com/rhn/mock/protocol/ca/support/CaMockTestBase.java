package com.rhn.mock.protocol.ca.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** CA 测试公共基类：随机端口起整套服务，每个用例前后 reset（同时复位运行时配置）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class CaMockTestBase {
    @LocalServerPort
    protected int port;

    protected CaTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new CaTestClient(port);
        client.reset();
    }

    @AfterEach
    void tearDownClient() {
        client.reset();
    }
}
