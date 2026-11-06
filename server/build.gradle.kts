plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.readygym"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// bootRun 里应用进程继承的是 Gradle 守护进程的环境变量：命令行里刚 set 的变量经守护进程转手会丢。
// -P 属性随构建命令传递、不受守护进程影响，所以本地启动用 -PreadyGymAdminToken 注入 token
// （启动运营面板.bat 走这条路）；环境变量作为后备。
val bootAdminToken = providers.gradleProperty("readyGymAdminToken")
    .orElse(providers.environmentVariable("READYGYM_ADMIN_TOKEN"))
    .orElse(providers.provider { "" })

tasks.bootRun {
    environment("READYGYM_ADMIN_TOKEN", bootAdminToken.get())
}
