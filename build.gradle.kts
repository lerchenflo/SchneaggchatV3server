plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("com.google.devtools.ksp") version "2.3.14"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.lombok") version "2.4.21"
}

group = "com.lerchenflo"
version = "3.0.20"
description = "SchneaggchatV3 server"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    //Email sending
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.projectlombok:lombok")

    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    implementation("tools.jackson.module:jackson-module-kotlin") // Jackson 3 Kotlin support (HTTP bodies + Json.mapper), so Kotlin default values apply

    //firebase
    implementation("com.google.firebase:firebase-admin:9.12.0")

    //APNs
    implementation("com.eatthepath:pushy:0.15.6")
    runtimeOnly("io.netty:netty-tcnative-boringssl-static:2.0.85.Final")


    implementation("org.springframework.boot:spring-boot-starter-websocket")

    //rate limiting
    implementation("com.bucket4j:bucket4j_jdk17-core:8.22.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.22.0")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    //testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.14.12")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
