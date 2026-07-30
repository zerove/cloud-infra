plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":cloud-infra-common"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson")
}
