package com.cloudinfra.system.plugin;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 热部署监听器 - 实时监控插件目录
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotDeploymentWatcher {

    private final PluginManager pluginManager;
    private final String pluginsDir = "plugins";
    private ExecutorService watchExecutor;

    @PostConstruct
    public void init() {
        // 确保插件目录存在
        File dir = new File(pluginsDir);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("创建插件目录: {}", pluginsDir);
        }

        // 启动监听线程
        watchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "plugin-watcher");
            t.setDaemon(true);
            return t;
        });

        watchExecutor.submit(this::watchLoop);
    }

    private void watchLoop() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            Path path = Paths.get(pluginsDir);
            path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);

            log.info("热部署监听已启动，监控目录: {}", path.toAbsolutePath());

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    Path fileName = (Path) event.context();
                    File file = path.resolve(fileName).toFile();

                    if (fileName.toString().endsWith(".jar")) {
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            log.info("检测到插件更新: {}", fileName);
                            pluginManager.loadPlugin(file);
                        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            log.info("检测到插件删除: {}", fileName);
                            // 实际卸载需要根据 ID，这里简单处理：假设文件名即 ID 或通过扫描卸载
                            // 生产环境应维护 文件名 <-> PluginId 的映射
                            log.warn("插件删除事件已触发，请通过 API 执行精准卸载");
                        }
                    }
                }
                key.reset();
            }
        } catch (IOException | InterruptedException e) {
            log.error("热部署监听异常: {}", e.getMessage());
        }
    }
}