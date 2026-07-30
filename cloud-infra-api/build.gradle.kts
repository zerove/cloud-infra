plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":cloud-infra-common"))
    implementation("io.github.resilience4j:resilience4j-annotations")
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta")
}
