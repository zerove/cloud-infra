plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter")
    implementation(project(":cloud-infra-api"))
    implementation("cn.hutool:hutool-all")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation(project(":cloud-infra-common"))
    implementation(project(":cloud-infra-framework:framework-redis"))
    implementation(project(":cloud-infra-framework:framework-web"))
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    implementation("org.springframework.boot:spring-boot-starter-security")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    implementation(project(":cloud-infra-framework:framework-security"))
    implementation(project(":cloud-infra-framework:framework-tracing"))
    implementation(project(":cloud-infra-framework:framework-mybatis"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.data:spring-data-redis")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(project(":cloud-infra-test-support"))
}
