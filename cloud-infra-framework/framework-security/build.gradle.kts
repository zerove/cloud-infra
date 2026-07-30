plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":cloud-infra-common"))
    implementation(project(":cloud-infra-api"))
    implementation("io.jsonwebtoken:jjwt-api")
    implementation("io.jsonwebtoken:jjwt-impl")
    implementation("io.jsonwebtoken:jjwt-jackson")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-starter-json")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    implementation("org.springframework.security:spring-security-web")
    implementation("org.springframework.security:spring-security-config")
}
