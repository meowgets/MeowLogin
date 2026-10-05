plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "com.meowgets.btc"
version = "1.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // TelegramBots для лонг-поллинга
    implementation("org.telegram:telegrambots-longpolling:7.10.0")
    implementation("org.telegram:telegrambots-client:7.10.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        // Релокация, чтобы не конфликтовать с другими плагинами
        relocate("org.telegram", "com.meowgets.btc.telegram")
    }
    build {
        dependsOn(shadowJar)
    }
}