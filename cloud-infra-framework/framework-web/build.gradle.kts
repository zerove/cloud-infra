plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.github.openfeign:feign-core")
    implementation(project(":cloud-infra-framework:framework-tracing"))
    implementation("io.micrometer:micrometer-core")
    implementation(project(":cloud-infra-framework:framework-redis"))

    testImplementation(project(":cloud-infra-test-support"))
}
