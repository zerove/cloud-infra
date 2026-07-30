plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:junit-jupiter")
    implementation("org.postgresql:postgresql")
    implementation("org.testcontainers:postgresql")
    implementation("org.testcontainers:rabbitmq")
    implementation("org.testcontainers:elasticsearch")
    implementation("org.springframework.boot:spring-boot-testcontainers")
}
