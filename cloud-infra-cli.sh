#!/bin/bash
# ============================================================
# Cloud-Infra 开发者脚手架
# 一条命令生成新业务服务
# 用法:
#   ./cloud-infra-cli.sh new service --name order-service --module order --port 8090
#   ./cloud-infra-cli.sh new service --name payment-service --module payment
# ============================================================
set -e

# 项目根目录
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULES_DIR="$ROOT_DIR/cloud-infra-modules"

print_usage() {
    echo "用法:"
    echo "  $0 new service --name <service-name> [--module <module-name>] [--port <port>] [--native]"
    echo ""
    echo "选项:"
    echo "  --name    服务名 (必填, 如 order-service)"
    echo "  --module  Maven 模块名 (默认与服务名相同)"
    echo "  --port    服务端口 (默认 8090)"
    echo "  --native  启用 Native Image 支持"
    echo "  --help    显示帮助"
}

# 参数解析
SERVICE_NAME=""
MODULE_NAME=""
PORT=8090
NATIVE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        new) shift ;;
        service) shift ;;
        --name) SERVICE_NAME="$2"; shift 2 ;;
        --module) MODULE_NAME="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --native) NATIVE=true; shift ;;
        --help|-h) print_usage; exit 0 ;;
        *) echo "未知参数: $1"; print_usage; exit 1 ;;
    esac
done

if [ -z "$SERVICE_NAME" ]; then
    echo "错误: --name 参数必填"
    print_usage
    exit 1
fi

MODULE_NAME=${MODULE_NAME:-$SERVICE_NAME}
SERVICE_DIR="$MODULES_DIR/$SERVICE_NAME"
PACKAGE_NAME=$(echo "$MODULE_NAME" | sed 's/-//g')
MAIN_CLASS="$(echo "$MODULE_NAME" | sed 's/-\([a-z]\)/\U\1/g; s/^\([a-z]\)/\U\1/')Application"

echo "============================================"
echo " Cloud-Infra 服务脚手架"
echo " 服务名: $SERVICE_NAME"
echo " 模块名: $MODULE_NAME"
echo " 端口: $PORT"
echo " Native: $NATIVE"
echo " 目录: $SERVICE_DIR"
echo "============================================"

if [ -d "$SERVICE_DIR" ]; then
    echo "错误: 目录已存在: $SERVICE_DIR"
    exit 1
fi

mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME"
mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/controller"
mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/service"
mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/service/impl"
mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/domain"
mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/domain/entity"
mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/domain/dto"
mkdir -p "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/mapper"
mkdir -p "$SERVICE_DIR/src/main/resources"
mkdir -p "$SERVICE_DIR/src/test/java/com/cloudinfra/$PACKAGE_NAME"
mkdir -p "$SERVICE_DIR/src/test/resources"

# ========== pom.xml ==========
cat > "$SERVICE_DIR/pom.xml" << 'POM_EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>cloud-infra-modules</artifactId>
        <groupId>com.cloudinfra</groupId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <artifactId>SERVICE_NAME</artifactId>
    <packaging>jar</packaging>

    <properties>
        <main.class>com.cloudinfra.PACKAGE_NAME.MAIN_CLASS</main.class>
    </properties>

    <dependencies>
        <!-- 平台聚合 Starter：一个依赖引入全部平台能力 -->
        <dependency>
            <groupId>com.cloudinfra</groupId>
            <artifactId>cloud-infra-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudinfra</groupId>
            <artifactId>cloud-infra-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudinfra</groupId>
            <artifactId>cloud-infra-api</artifactId>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.cloudinfra</groupId>
            <artifactId>cloud-infra-test-support</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
POM_EOF

# 替换模板变量
sed -i "s/SERVICE_NAME/$SERVICE_NAME/g; s/PACKAGE_NAME/$PACKAGE_NAME/g; s/MAIN_CLASS/$MAIN_CLASS/g" "$SERVICE_DIR/pom.xml"

# ========== 主应用类 ==========
cat > "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/$MAIN_CLASS.java" << APP_EOF
package com.cloudinfra.$PACKAGE_NAME;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.cloudinfra.api")
@EnableTransactionManagement
@SpringBootApplication(scanBasePackages = {"com.cloudinfra"})
public class $MAIN_CLASS {
    public static void main(String[] args) {
        SpringApplication.run($MAIN_CLASS.class, args);
    }
}
APP_EOF

# ========== 示例 Controller ==========
cat > "$SERVICE_DIR/src/main/java/com/cloudinfra/$PACKAGE_NAME/controller/HealthController.java" << CTRL_EOF
package com.cloudinfra.$PACKAGE_NAME.controller;

import com.cloudinfra.common.core.domain.Result;
import com.cloudinfra.common.core.security.PlatformPrincipal;
import com.cloudinfra.common.core.security.PlatformSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查与平台能力示例
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of(
            "status", "UP",
            "service", "$SERVICE_NAME",
            "authenticated", PlatformSecurityContext.isAuthenticated()
        ));
    }

    @GetMapping("/whoami")
    public Result<PlatformPrincipal> whoami() {
        return Result.success(PlatformSecurityContext.current());
    }
}
CTRL_EOF

# ========== application.yml ==========
cat > "$SERVICE_DIR/src/main/resources/application.yml" << YML_EOF
server:
  port: PORT

spring:
  application:
    name: SERVICE_NAME
  config:
    import: "optional:nacos:SERVICE_NAME.yaml"
  cloud:
    nacos:
      config:
        server-addr: \${NACOS_CONFIG_ADDR:\${NACOS_ADDR:nacos-service:8848}}
        file-extension: yaml
        shared-configs:
          - data-id: actuator-config.yaml
            refresh: true
        import-check:
          enabled: false
      discovery:
        server-addr: \${NACOS_DISCOVERY_ADDR:\${NACOS_ADDR:nacos-service:8848}}
  main:
    allow-bean-definition-overriding: true

# 平台能力配置
cloud-infra:
  tenant:
    enabled: false
    require-tenant: false
  outbox:
    enabled: true
    poll-interval-ms: 1000
    batch-size: 100
    cleanup-days: 7

management:
  server:
    port: 9301
  endpoints:
    web:
      base-path: /management
      exposure:
        include: "*"
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://otel-collector:4317
YML_EOF

sed -i "s/PORT/$PORT/g; s/SERVICE_NAME/$SERVICE_NAME/g" "$SERVICE_DIR/src/main/resources/application.yml"

# ========== 测试 ==========
cat > "$SERVICE_DIR/src/test/java/com/cloudinfra/$PACKAGE_NAME/HealthControllerTest.java" << TEST_EOF
package com.cloudinfra.$PACKAGE_NAME;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 服务启动冒烟测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HealthControllerTest {

    @Autowired(required = false)
    private org.springframework.context.ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
TEST_EOF

# ========== Dockerfile ==========
cat > "$SERVICE_DIR/Dockerfile" << DOCKER_EOF
# 构建阶段
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE PORT
ENTRYPOINT ["java", "-jar", "app.jar"]
DOCKER_EOF

sed -i "s/PORT/$PORT/g" "$SERVICE_DIR/Dockerfile"

# ========== Helm Chart ==========
mkdir -p "$SERVICE_DIR/deploy/k8s"
cat > "$SERVICE_DIR/deploy/k8s/values.yaml" << HELM_EOF
replicaCount: 2
image:
  repository: $SERVICE_NAME
  tag: latest
service:
  port: $PORT
resources:
  limits:
    cpu: 1000m
    memory: 512Mi
  requests:
    cpu: 500m
    memory: 256Mi
HELM_EOF

# ========== 注册到父 POM ==========
if grep -q "cloud-infra-modules" "$ROOT_DIR/pom.xml"; then
    # 注册到 cloud-infra-modules 的 pom.xml
    MODULES_POM="$MODULES_DIR/pom.xml"
    if [ -f "$MODULES_POM" ] && ! grep -q "<module>$SERVICE_NAME</module>" "$MODULES_POM"; then
        sed -i "s|</modules>|        <module>$SERVICE_NAME</module>\n    </modules>|" "$MODULES_POM"
        echo "已注册模块到 cloud-infra-modules/pom.xml"
    fi
fi

echo ""
echo "============================================"
echo " 服务创建成功!"
echo ""
echo " 目录: $SERVICE_DIR"
echo " 下一步:"
echo "   cd $SERVICE_DIR && mvn clean package -DskipTests"
echo "   mvn spring-boot:run"
echo "============================================"