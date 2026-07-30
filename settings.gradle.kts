pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

rootProject.name = "cloud-infra"

include("cloud-infra-common")
include("cloud-infra-api")
include("cloud-infra-gateway")
include("cloud-infra-auth")
include("cloud-infra-test-support")

// Framework modules
include("cloud-infra-framework:framework-security")
include("cloud-infra-framework:framework-tracing")
include("cloud-infra-framework:framework-redis")
include("cloud-infra-framework:framework-web")
include("cloud-infra-framework:framework-mybatis")

// Business modules
include("cloud-infra-modules:system-module")
include("cloud-infra-modules:notification-service")
include("cloud-infra-modules:search-service")
include("cloud-infra-modules:file-service")

// Map nested projects to their actual directories if they differ from default
project(":cloud-infra-framework:framework-security").projectDir = file("cloud-infra-framework/framework-security")
project(":cloud-infra-framework:framework-tracing").projectDir = file("cloud-infra-framework/framework-tracing")
project(":cloud-infra-framework:framework-redis").projectDir = file("cloud-infra-framework/framework-redis")
project(":cloud-infra-framework:framework-web").projectDir = file("cloud-infra-framework/framework-web")
project(":cloud-infra-framework:framework-mybatis").projectDir = file("cloud-infra-framework/framework-mybatis")

project(":cloud-infra-modules:system-module").projectDir = file("cloud-infra-modules/system-module")
project(":cloud-infra-modules:notification-service").projectDir = file("cloud-infra-modules/notification-service")
project(":cloud-infra-modules:search-service").projectDir = file("cloud-infra-modules/search-service")
project(":cloud-infra-modules:file-service").projectDir = file("cloud-infra-modules/file-service")
