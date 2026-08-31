package com.cloudinfra.codegen.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodegenConfig 单元测试
 */
class CodegenConfigTest {

    @Test
    void testDefaultValues() {
        CodegenConfig config = new CodegenConfig();
        assertEquals("com.cloudinfra", config.getParent());
        assertEquals("system", config.getModuleName());
        assertEquals("sys_", config.getTableNamePrefix());
    }

    @Test
    void testDerivedPackageName() {
        CodegenConfig config = new CodegenConfig();
        config.setModuleName("file");
        assertEquals("com.cloudinfra.file", config.getPackageName());
    }

    @Test
    void testPackageNameOverride() {
        CodegenConfig config = new CodegenConfig();
        config.setModuleName("file");
        config.setPackageName("com.custom.file");
        assertEquals("com.custom.file", config.getPackageName());
    }

    @Test
    void testDerivedControllerPrefix() {
        CodegenConfig config = new CodegenConfig();
        config.setModuleName("system");
        assertEquals("/system", config.getControllerPrefix());
    }

    @Test
    void testDerivedPermissionPrefix() {
        CodegenConfig config = new CodegenConfig();
        config.setModuleName("notification");
        assertEquals("notification", config.getPermissionPrefix());
    }

    @Test
    void testGenerateFlags() {
        CodegenConfig config = new CodegenConfig();
        assertTrue(config.isGenerateController());
        assertTrue(config.isGenerateService());
        assertTrue(config.isGenerateMapper());
        assertTrue(config.isGenerateEntity());
        assertTrue(config.isGenerateDto());
        assertTrue(config.isGenerateVo());
        assertTrue(config.isGenerateConverter());
        assertTrue(config.isGenerateSql());
        assertTrue(config.isSkipIfExists());
    }
}
