package com.cloudinfra.test.support.containers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * 基础设施容器测试底座
 * 利用 Testcontainers 的 Wait Strategy，自动处理容器的启动就绪检测
 */
public class InfrastructureContainers {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("cloud_infra")
        .withUsername("postgres")
        .withPassword("password");

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    public static final GenericContainer<?> NACOS = new GenericContainer<>("nacos/nacos-server:v3.0.2")
        .withExposedPorts(8848)
        .withEnv("MODE", "standalone")
        .waitingFor(Wait.forLogMessage(".*Nacos started successfully.*", 1));

    static {
        POSTGRES.start();
        REDIS.start();
        NACOS.start();
    }
}
