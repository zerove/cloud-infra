package com.cloudinfra.system.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.cloudinfra.common.core.exception.GlobalException;
import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.service.impl.SysDeptServiceImpl;

@SpringBootTest(properties = {
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.config.enabled=false"
})
public class SysDeptServiceTest {

    @Autowired
    private SysDeptServiceImpl deptService;

    @Test
    public void testInsertDept_DepthExceed() {
        SysDept dept = new SysDept();
        dept.setAncestors("0,1,2,3,4,5"); // 深度超过 5

        assertThrows(GlobalException.class, () -> {
            deptService.insertDept(dept);
        }, "部门层级最多支持 5 级");
    }
}
