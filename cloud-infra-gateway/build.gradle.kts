plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery")
    implementation(project(":cloud-infra-common"))
    implementation(project(":cloud-infra-framework:framework-redis"))
    implementation(project(":cloud-infra-framework:framework-security"))
    implementation(project(":cloud-infra-framework:framework-tracing"))
    implementation("cn.hutool:hutool-all")
    implementation("com.alibaba.cloud:spring-cloud-alibaba-sentinel-gateway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.alibaba.csp:sentinel-spring-cloud-gateway-v6x-adapter:1.8.9")
    implementation("com.alibaba.csp:sentinel-datasource-nacos")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.58")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":cloud-infra-test-support"))
}
