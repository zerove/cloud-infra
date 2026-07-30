plugins {
    java
    id("io.spring.dependency-management")
}

dependencies {
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    implementation("com.github.ulisesbocchio:jasypt-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.aspectj:aspectjrt:1.9.22.1")
    implementation("org.aspectj:aspectjweaver:1.9.22.1")
    implementation("com.baomidou:mybatis-plus-annotation")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.hibernate.validator:hibernate-validator")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("com.alibaba:transmittable-thread-local:2.14.5")
    implementation("cn.hutool:hutool-all")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

// Support for test-jar (equivalent to maven-jar-plugin:test-jar)
tasks.register<Jar>("testJar") {
    archiveClassifier.set("tests")
    from(sourceSets.test.get().output)
}
