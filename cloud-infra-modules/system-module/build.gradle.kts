plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/java_new"))
    }
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.cloud:spring-cloud-starter-bootstrap")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    implementation(project(":cloud-infra-framework:framework-web"))
    implementation(project(":cloud-infra-framework:framework-security"))
    implementation(project(":cloud-infra-framework:framework-tracing"))
    implementation(project(":cloud-infra-framework:framework-mybatis"))
    implementation(project(":cloud-infra-api"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(project(":cloud-infra-common"))
    implementation(project(":cloud-infra-framework:framework-redis"))
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery")
    implementation("org.springframework.cloud:spring-cloud-stream-binder-rabbit")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(project(":cloud-infra-test-support"))
}
