plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery")
    implementation(project(":cloud-infra-framework:framework-web"))
    implementation(project(":cloud-infra-framework:framework-tracing"))
    implementation(project(":cloud-infra-framework:framework-mybatis"))
    implementation(project(":cloud-infra-framework:framework-security"))
    implementation(project(":cloud-infra-api"))
    implementation("io.swagger.core.v3:swagger-annotations-jakarta")
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":cloud-infra-test-support"))
}
