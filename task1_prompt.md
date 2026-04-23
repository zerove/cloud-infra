任务 1: 添加 Actuator 和 Prometheus 依赖到主 pom.xml 的 dependencyManagement 中。
内容: 
在 cloud-infra/pom.xml 的 <dependencyManagement> 区域添加:
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
    <version>${spring-boot.version}</version>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <version>1.14.0</version>
</dependency>
