package com.cloudinfra.system.plugin;

import com.cloudinfra.framework.plugin.IServicePlugin;
import com.cloudinfra.system.plugin.impl.MediaAiTaggingPlugin;
import com.cloudinfra.system.plugin.impl.KnowledgeAssistantPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件管理器 - 负责插件的动态加载、生命周期管理与热部署
 *
 * <p>安全策略: URLClassLoader 可加载任意 jar 路径, 加载代码继承 system-module 全部权限
 * (调任意 service / 读任意 DB / 调任意 Feign). 生产环境建议关闭
 * ({@code cloud-infra.plugin.enabled=false}), 仅 dev 启用.</p>
 *
 * <p>详见 ADR-010 plugin-security-model.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cloud-infra.plugin.enabled", havingValue = "true", matchIfMissing = true)
public class PluginManager {

    private final PluginContextImpl pluginContext;
    private final PluginRegistry pluginRegistry;

    // 存储已加载的插件及其类加载器 (用于卸载时关闭)
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();

    private record LoadedPlugin(IServicePlugin instance, URLClassLoader classLoader) {}

    /**
     * 启动时预加载内置插件 (用于演示)
     */
    @PostConstruct
    public void bootloadBuiltinPlugins() {
        log.info("正在预加载内置插件...");
        try {
            // 1. 预加载 AI 打标签插件
            MediaAiTaggingPlugin aiTaggingPlugin = new MediaAiTaggingPlugin();
            aiTaggingPlugin.onEnable(pluginContext);
            loadedPlugins.put(aiTaggingPlugin.getMetadata().id(), new LoadedPlugin(aiTaggingPlugin, null));
            log.info("内置插件 {} 预加载成功", aiTaggingPlugin.getMetadata().name());

            // 2. 预加载 联邦知识助手插件
            KnowledgeAssistantPlugin knowledgeAssistant = new KnowledgeAssistantPlugin();
            knowledgeAssistant.onEnable(pluginContext);
            loadedPlugins.put(knowledgeAssistant.getMetadata().id(), new LoadedPlugin(knowledgeAssistant, null));
            log.info("内置插件 {} 预加载成功", knowledgeAssistant.getMetadata().name());
            
        } catch (RuntimeException e) {
            log.error("预加载内置插件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 加载指定路径的插件 JAR
     */
    public void loadPlugin(File jarFile) {
        log.info("尝试加载插件: {}", jarFile.getName());

        try {
            URL[] urls = { jarFile.toURI().toURL() };
            // 父加载器用 TCCL: 插件需要能看到宿主应用经自定义加载器引入的类,
            // 用本类的 ClassLoader 会导致 NoClassDefFoundError
            URLClassLoader classLoader = new URLClassLoader(urls,
                    Thread.currentThread().getContextClassLoader());

            Properties props = new Properties();
            try (InputStream is = classLoader.getResourceAsStream("plugin.properties")) {
                if (is == null) {
                    log.error("插件 {} 缺失 plugin.properties 文件", jarFile.getName());
                    classLoader.close();
                    return;
                }
                props.load(is);
            }

            String mainClassName = props.getProperty("main.class");
            if (mainClassName == null) {
                log.error("插件 {} 的 plugin.properties 未定义 main.class", jarFile.getName());
                classLoader.close();
                return;
            }

            Class<?> pluginClass = classLoader.loadClass(mainClassName);
            if (!IServicePlugin.class.isAssignableFrom(pluginClass)) {
                log.error("插件入口类 {} 未实现 IServicePlugin 接口", mainClassName);
                classLoader.close();
                return;
            }

            IServicePlugin plugin = (IServicePlugin) pluginClass.getDeclaredConstructor().newInstance();
            String pluginId = plugin.getMetadata().id();

            plugin.onEnable(pluginContext);
            loadedPlugins.put(pluginId, new LoadedPlugin(plugin, classLoader));
            log.info("插件 {} ({}) 加载成功！", plugin.getMetadata().name(), pluginId);

        } catch (RuntimeException | ReflectiveOperationException | IOException e) {
            log.error("加载插件 {} 失败: {}", jarFile.getName(), e.getMessage(), e);
        }
    }

    /**
     * 卸载指定 ID 的插件
     */
    public void unloadPlugin(String pluginId) {
        LoadedPlugin lp = loadedPlugins.remove(pluginId);
        if (lp == null) {
            return;
        }

        try {
            lp.instance().onDisable();
            if (lp.classLoader() != null) {
                lp.classLoader().close();
            }
            log.info("插件 {} 已成功卸载", pluginId);
        } catch (IOException e) {
            log.error("卸载插件 {} 时关闭类加载器失败: {}", pluginId, e.getMessage());
        }
    }

    public Map<String, IServicePlugin> getActivePlugins() {
        Map<String, IServicePlugin> active = new HashMap<>();
        loadedPlugins.forEach((id, lp) -> active.put(id, lp.instance()));
        return active;
    }
}
