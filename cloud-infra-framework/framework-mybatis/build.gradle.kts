plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjrt:1.9.22")
    implementation("org.aspectj:aspectjweaver:1.9.22")
    implementation(project(":cloud-infra-common"))
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter")
    runtimeOnly("org.postgresql:postgresql:42.7.2")
}
