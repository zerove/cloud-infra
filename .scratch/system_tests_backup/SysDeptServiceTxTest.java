package com.cloudinfra.system.service.impl;

import com.cloudinfra.system.domain.SysDept;
import com.cloudinfra.system.service.ISysDeptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SysDeptServiceTxTest {

    @Autowired
    private ISysDeptService deptService;

    @Test
    public void testInsertDeptRollback() {
        SysDept dept = new SysDept();
        dept.setDeptName("Test Dept");
        dept.setParentId(0L);

        try {
            deptService.insertDeptWithException(dept);
        } catch (Exception e) {
            // expected
        }

        assertNull(deptService.selectDeptList(dept).stream()
                .filter(d -> d.getDeptName().equals("Test Dept"))
                .findFirst().orElse(null));
    }
}
