plugins {
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    kotlin("jvm") version "2.0.0" apply false
    java
}

allprojects {
    group = "com.cloudinfra"
    version = "1.0.0-SNAPSHOT"

    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    dependencies {
        implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.5"))
        implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.1"))
        implementation(platform("com.alibaba.cloud:spring-cloud-alibaba-dependencies:2025.1.0.0"))
        
        compileOnly("org.projectlombok:lombok:1.18.38")
        annotationProcessor("org.projectlombok:lombok:1.18.38")
        testImplementation("org.projectlombok:lombok:1.18.38")
        testAnnotationProcessor("org.projectlombok:lombok:1.18.38")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }
}
