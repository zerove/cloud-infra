package com.cloudinfra.codegen;

import com.cloudinfra.codegen.config.CodegenConfig;
import com.cloudinfra.codegen.generator.CodegenGenerator;
import com.cloudinfra.codegen.generator.TableMetadataReader;
import com.cloudinfra.codegen.model.TableModel;
import com.cloudinfra.codegen.util.DataSourceFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

/**
 * 代码生成 CLI 入口
 *
 * <p>使用方式:
 * <pre>
 * java -cp cloud-infra-codegen.jar com.cloudinfra.codegen.CodegenCli
 * </pre>
 */
public class CodegenCli {

    public static void main(String[] args) {
        System.out.println("=== Cloud-Infra 代码生成工具 v1.0.0 ===");
        System.out.println("自动生成 Entity/Mapper/Service/Controller/DTO/VO/Converter");

        try (Scanner scanner = new Scanner(System.in)) {
            // 1. 读取数据库配置
            System.out.print("\n[1/6] 请输入数据库 URL (默认: jdbc:postgresql://localhost:5432/cloud_infra): ");
            String dbUrl = scanner.nextLine().trim();
            if (dbUrl.isEmpty()) {
                dbUrl = "jdbc:postgresql://localhost:5432/cloud_infra";
            }

            System.out.print("[2/6] 请输入数据库用户名 (默认: root): ");
            String dbUsername = scanner.nextLine().trim();
            if (dbUsername.isEmpty()) {
                dbUsername = "root";
            }

            System.out.print("[3/6] 请输入数据库密码: ");
            String dbPassword = scanner.nextLine().trim();

            System.out.print("[4/6] 请输入表名 (多个表名用逗号分隔, 输入 all 表示全部): ");
            String tableNamesInput = scanner.nextLine().trim();

            System.out.print("[5/6] 请输入模块名 (system/file/notification/search): ");
            String moduleName = scanner.nextLine().trim();
            if (moduleName.isEmpty()) {
                moduleName = "system";
            }

            System.out.print("[6/6] 请输入输出目录 (默认: 项目根目录): ");
            String outputDir = scanner.nextLine().trim();
            if (outputDir.isEmpty()) {
                outputDir = System.getProperty("user.dir") + "/../";
            }

            // 配置代码生成
            CodegenConfig config = new CodegenConfig();
            config.setOutputDir(outputDir);
            config.setModuleName(moduleName);
            config.setPackageName("com.cloudinfra." + moduleName);
            config.setControllerPrefix("/" + moduleName);
            config.setPermissionPrefix(moduleName);

            // 配置数据库连接
            Properties props = new Properties();
            props.setProperty("url", dbUrl);
            props.setProperty("username", dbUsername);
            props.setProperty("password", dbPassword);

            // 创建数据源
            DataSource dataSource = DataSourceFactory.create(props);

            // 创建生成器
            CodegenGenerator generator = new CodegenGenerator(config);
            TableMetadataReader reader = new TableMetadataReader(dataSource);

            // 生成代码
            if ("all".equalsIgnoreCase(tableNamesInput)) {
                System.out.println("\n正在读取全部表结构...");
                List<TableModel> tables = reader.readAllTables();
                System.out.println("发现 " + tables.size() + " 个表");
                for (TableModel table : tables) {
                    generator.generate(table);
                }
            } else {
                String[] tableNames = tableNamesInput.split(",");
                for (String tableName : tableNames) {
                    tableName = tableName.trim();
                    if (!tableName.isEmpty()) {
                        TableModel table = reader.readTable(tableName);
                        generator.generate(table);
                    }
                }
            }

            System.out.println("\n✅ 代码生成完成！");
            System.out.println("   输出目录: " + outputDir + "cloud-infra-modules/" + moduleName + "/");

        } catch (SQLException | IOException | RuntimeException e) {
            System.err.println("\n❌ 代码生成失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
