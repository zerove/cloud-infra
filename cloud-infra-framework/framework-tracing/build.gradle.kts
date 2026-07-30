plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":cloud-infra-common"))
    implementation("org.apache.skywalking:apm-toolkit-logback-1.x:9.2.0")
    implementation("org.apache.skywalking:apm-toolkit-trace:9.2.0")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-tracing")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":cloud-infra-test-support"))
}
