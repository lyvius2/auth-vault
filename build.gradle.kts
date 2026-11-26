plugins {
    java
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "banghak.home"
version = "0.2.2"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:1.1.0") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

graalvmNative {
    binaries.named("main") {
        imageName = "auth-vault"
        buildArgs.add("--no-fallback")
        buildArgs.add("-Djdk.httpclient.allowRestrictedHeaders=host")
    }
}

tasks.test { useJUnitPlatform() }