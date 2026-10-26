import org.gradle.plugin.compatibility.compatibility

plugins {
    java
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.2.1"
    `maven-publish`
    signing
}

group = "dev.oblivruin"
version = "1.0.0"

java {
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // JUnit 5 line: Java 8 compatible, so the whole build can run on JDK 8 through 25
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // TestKit for the functional tests
    testImplementation(gradleTestKit())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // The plugin is written in Java-8 compatible syntax and is published as Java 8
    // bytecode, so it can be loaded by any Gradle 8.x build (Java 8+ runtime) as well
    // as Gradle 9 (Java 17+ runtime). The `--release` flag requires a JDK 9+ compiler;
    // when the build itself runs on a JDK 8 compiler the default target is already 8.
    // NOTE: building this project itself requires a JDK between 17 and 22 - the plugin
    // publishing plugin requires a Java 17+ runtime, and Gradle 8.8 cannot run on JDK 23+.
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

gradlePlugin {
    website = "https://github.com/OblivRuinDev/preprocessor"
    vcsUrl = "https://github.com/OblivRuinDev/preprocessor"
    plugins {
        create("preprocessor") {
            id = "dev.oblivruin.preprocessor"
            implementationClass = "dev.oblivruin.preprocessor.PreProcessorPlugin"
            displayName = "Preprocessor"
            description = "A flexible source preprocessing plugin: conditional blocks (//#if / //\$\$), custom " +
                    "directives, custom processors and per source set / per language configuration."

            compatibility {
                features.configurationCache = true
            }
        }
    }
}
