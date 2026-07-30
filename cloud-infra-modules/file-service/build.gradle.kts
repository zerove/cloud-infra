plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":cloud-infra-framework:framework-web"))
    implementation(project(":cloud-infra-framework:framework-mybatis"))
    implementation(project(":cloud-infra-framework:framework-tracing"))
    implementation(project(":cloud-infra-framework:framework-security"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.swagger.core.v3:swagger-annotations-jakarta")
    implementation("io.minio:minio:8.5.7")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":cloud-infra-test-support"))
    testImplementation("com.h2database:h2")
}
