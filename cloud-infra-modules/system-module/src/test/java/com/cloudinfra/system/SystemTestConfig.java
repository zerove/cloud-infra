package com.cloudinfra.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 专门用于系统模块测试的配置，扫描 Mapper 路径
 */
@SpringBootApplication(scanBasePackages = "com.cloudinfra.system")
@MapperScan("com.cloudinfra.system.mapper")
public class SystemTestConfig {
}
