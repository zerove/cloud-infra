package com.cloudinfra.system.service;

import com.cloudinfra.system.domain.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.cloud.bootstrap.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.application.name=cloud-system",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "seata.enabled=true",
        "seata.tx-service-group=cloud-infra-system",
        "seata.service.vgroup-mapping.cloud-infra-system=default",
        "seata.registry.type=none"
    }
)
public class SeataTransactionTest {

    @Autowired
    private ISysUserService sysUserService;

    @Test
    void testGlobalTransactionRollback() {
        // 1. 准备数据
        Long userId = 1L; // 假设数据库已有 ID 为 1 的用户
        String originalNickName = "OriginalName";
        String newNickName = "UpdatedName";

        // 2. 调用带有 @GlobalTransactional 的方法，预期抛出异常
        assertThrows(RuntimeException.class, () -> {
            sysUserService.verifySeataTransaction(userId, newNickName);
        });

        // 3. 验证数据是否回滚
        com.cloudinfra.system.domain.SysUser user = sysUserService.selectUserById(userId);
        assertNotEquals(newNickName, user.getNickName(), "数据应当回滚，昵称不应被更新");
    }
}
