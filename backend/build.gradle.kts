plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.saadat"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters (versions managed by the Boot BOM)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Auth
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.google.api-client:google-api-client:2.7.0")

    // Passkeys (WebAuthn verification, Apache-2.0). webauthn4j runs on Jackson 3 (tools.jackson.*, next to Spring's
    // Jackson 2), which keeps using the 2.x annotations jar at 2.22; this explicit version beats Boot 3.3's managed
    // 2.17 (annotations are backward compatible, Spring's Jackson 2 databind works with them unchanged).
    implementation("com.webauthn4j:webauthn4j-core:0.31.11.RELEASE")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.22")

    // Rate limiting
    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")

    // Web push (exclude the old jdk15on BouncyCastle; we pin jdk18on below)
    implementation("nl.martijndwars:web-push:5.1.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // API docs + error tracking
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    implementation("io.sentry:sentry-spring-boot-starter-jakarta:7.16.0")

    // Reports: PDF (HTML → PDF with ICU bidi/shaping for Arabic) and Excel (streaming .xlsx)
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.87") {
        exclude(group = "commons-logging", module = "commons-logging") // spring-jcl provides the API
    }
    implementation("io.github.openhtmltopdf:openhtmltopdf-rtl-support:1.1.87")
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Tests
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("com.icegreen:greenmail-junit5:2.1.0")
    // emulated authenticators (real keys and signatures) for the passkey tests
    testImplementation("com.webauthn4j:webauthn4j-test:0.31.11.RELEASE")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Only produce the executable Boot jar (build/libs/app.jar); no "-plain" jar.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
