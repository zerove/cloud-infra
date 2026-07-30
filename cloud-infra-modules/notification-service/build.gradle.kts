plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.baomidou:mybatis-plus-extension")
    implementation(project(":cloud-infra-framework:framework-mybatis"))
    implementation(project(":cloud-infra-framework:framework-tracing"))
    implementation(project(":cloud-infra-framework:framework-redis"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":cloud-infra-framework:framework-security"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    implementation(project(":cloud-infra-common"))
    implementation(project(":cloud-infra-api"))
    implementation("org.springframework.cloud:spring-cloud-stream-binder-rabbit")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation(project(":cloud-infra-test-support"))
}
