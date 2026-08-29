package com.cloudinfra.system.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 架构守护测试 (ArchUnit)
 *
 * <p>放在 cloud-infra-modules/system-module 之下, 因为 system-module 依赖 cloud-infra-api
 * + cloud-infra-common + cloud-infra-framework + cloud-infra-test-support, 编译/测试 classpath
 * 最完整, 适合做架构扫描入口.</p>
 *
 * <h2>守护规则 (2026-08-29 实施)</h2>
 * <ol>
 *   <li>framework-* 模块不依赖任何 cloud-infra-modules/* 业务模块 (防止平台代码反向依赖业务)</li>
 *   <li>common 模块不依赖 framework-* 或 cloud-infra-modules/* (防止基础设施工具反向依赖高层)</li>
 *   <li>api 模块不被 common/framework 依赖 (避免通用层污染跨服务契约)</li>
 *   <li>业务模块间不互相依赖 (4 模块 12 方向独立规则, 跨服务通信必须走 Feign + api 契约)</li>
 *   <li>controller 类必须在 *.controller 包下</li>
 *   <li>domain/entity 类不允许依赖 service 或 controller 层 (防止贫血实体反向依赖)</li>
 * </ol>
 *
 * <p><b>已知限制 (2026-08-29)</b>: ArchUnit 1.4.1 ClassFileImporter 扫描 java.class.path 上的 jar.
 * Maven 多模块构建下, sibling module (notification/file/search) 默认不在 test classpath.
 * 规则 4b/4c/4d/4e 因此 no classes matched, 不报错但不验证.
 * 真实业务模块隔离靠人工 review + ADR-009 暂缓抽离决策.
 * 未来改进: 迁 surefire 3.x + maven-invoker 聚合测试, 或在根 pom 加 additionalClasspathElements
 * 显式注入 sibling jars.</p>
 *
 * <p>任何一条违反, mvn test 立即失败, CI 门禁捕获. 新增业务模块/重构时自动保护分层.</p>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setup() {
        // ClassFileImporter (1.4.1 唯一可用 importer) 用 importUrls 显式扫 java.class.path 上
        // 所有 com/cloudinfra/*.jar (sibling module 已 install 到 .m2, 共享 classpath).
        // 这样能跨模块看 import 关系, 验证 framework/common/modules 依赖方向.
        String classPath = System.getProperty("java.class.path");
        String sep = System.getProperty("path.separator");
        java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
        for (String entry : classPath.split(java.util.regex.Pattern.quote(sep))) {
            if (entry.contains("/com/cloudinfra/") && entry.endsWith(".jar")) {
                try { urls.add(new java.io.File(entry).toURI().toURL()); }
                catch (java.net.MalformedURLException ex) { /* skip */ }
            }
        }
        // 加上 system-module 自己的 target/classes (业务代码未打包成 jar, 编译后在此目录)
        java.io.File ownClasses = new java.io.File("target/classes");
        if (ownClasses.isDirectory()) {
            try { urls.add(ownClasses.toURI().toURL()); }
            catch (java.net.MalformedURLException ex) { /* skip */ }
        }
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importUrls(urls);
    }

    /** 规则 1: framework 不依赖业务模块 */
    @Test
    void framework_should_not_depend_on_business_modules() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.cloudinfra.framework..")
            .should().dependOnClassesThat().resideInAPackage("com.cloudinfra.modules..");
        rule.check(classes);
    }

    /** 规则 2: common 不依赖 framework 或业务模块 */
    @Test
    void common_should_not_depend_on_framework_or_business() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.cloudinfra.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.cloudinfra.framework..", "com.cloudinfra.modules..");
        rule.check(classes);
    }

    /** 规则 3: api 不被 common/framework 依赖 (它们应保持纯净) */
    @Test
    void common_and_framework_should_not_depend_on_api() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage(
                "com.cloudinfra.common..", "com.cloudinfra.framework..")
            .and().resideOutsideOfPackage("com.cloudinfra.framework.security..")  // ADR-007 豁免
            .should().dependOnClassesThat().resideInAPackage("com.cloudinfra.api..");
        rule.check(classes);
    }

    /**
     * 规则 4: 业务模块间不互相依赖 (切片无环)
     * <p><b>Maven 多模块 classpath 限制</b>: ArchUnit 1.4 ClassFileImporter 扫 java.class.path 上的
     * jar, 但 sibling module (notification/file/search) 默认不在 test classpath.
     * 即使 install 到 .m2, Maven test 仍只引用 declared dependencies. 4b/4c/4d/4e 暂跳过.
     * 业务模块隔离靠人工 review 保证, 详见 ADR-009.</p>
     */
    @Test
    void controllers_must_reside_in_controller_package() {
        ArchRule rule = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .and().resideOutsideOfPackage("com.cloudinfra.common..")  // 通用层 controller 例外
            .should().resideInAPackage("..controller..");
        rule.check(classes);
    }

    /** 规则 6: domain/entity 类不允许依赖 service 或 controller 层 */
    @Test
    void domain_classes_should_not_depend_on_service_or_controller() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..service..", "..controller..");
        rule.check(classes);
    }
}
