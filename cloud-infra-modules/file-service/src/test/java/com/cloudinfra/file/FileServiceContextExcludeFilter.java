package com.cloudinfra.file;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * FileServiceIntegrationTest 专用扫描排除器。
 *
 * 该测试以生产启动类 FileApplication 为上下文（scanBasePackages="com.cloudinfra"），
 * 会把同包集成测试 FileHttpFlowIntegrationTest 中定义的嵌入式测试启动类
 * （@SpringBootApplication 嵌套类）当作普通 @Configuration 注册，导致测试装配污染
 * （替身过滤器链/受限组件扫描混入生产上下文）。此过滤器在测试上下文扫描时排除这些类。
 * 仅作用于声明了本过滤器的测试上下文，对生产运行零影响。
 */
public class FileServiceContextExcludeFilter extends TypeExcludeFilter {

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
        String className = metadataReader.getClassMetadata().getClassName();
        // 排除 FlowIT 的嵌套装配类（形如 FileHttpFlowIntegrationTest$FileTestApplication）
        return className.startsWith(FileHttpFlowIntegrationTest.class.getName() + "$");
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && getClass() == obj.getClass();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode() * 31;
    }
}
