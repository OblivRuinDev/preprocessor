import org.gradle.api.credentials.PasswordCredentials
import org.gradle.plugin.compatibility.compatibility

plugins {
    java
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.2.1"
    `maven-publish`
    signing
}

group = "dev.oblivruin"
// CI passes the release version on the command line (stripped from the git tag):
//   gradlew -PprojectVersion=1.2.3 ...
version = providers.gradleProperty("projectVersion").getOrElse("1.0.0")

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
    // NOTE: building this project itself requires a JDK of 17 or newer - the plugin
    // publishing plugin requires a Java 17+ runtime, and Gradle 9 requires a Java 17+ runtime.
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // optional Gradle version matrix for the demo functional tests:
    //   gradlew test -Pdemo.gradle.version=9.8.2 --tests "dev.oblivruin.preprocessor.Demo*"
    systemProperty("demo.gradle.version", providers.gradleProperty("demo.gradle.version").getOrElse(""))
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

// Maven Central publishing: the java-gradle-plugin publications (the plugin jar and the
// plugin marker) are published by the release workflow directly to the OSSRH compatibility
// staging API, from where the workflow transfers them to the Central Portal. The repository
// is named 'sonatype', so Gradle resolves the credentials from the sonatypeUsername /
// sonatypePassword project properties (the workflow provides them through the
// ORG_GRADLE_PROJECT_ environment variables).
publishing {
    repositories {
        maven {
            name = "sonatype"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials(PasswordCredentials::class)
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Preprocessor")
            description.set("A flexible source preprocessing plugin for Gradle: conditional blocks (//#if, //\$\$), " +
                    "custom directives, custom text processors and per source set / per language configuration.")
            url.set("https://github.com/OblivRuinDev/preprocessor")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("oblivruin")
                    name.set("OblivRuin")
                    url.set("https://github.com/OblivRuinDev")
                }
            }
            scm {
                url.set("https://github.com/OblivRuinDev/preprocessor")
                connection.set("scm:git:https://github.com/OblivRuinDev/preprocessor.git")
                developerConnection.set("scm:git:git@github.com:OblivRuinDev/preprocessor.git")
            }
        }
    }
}

// Signing is configured only when a key is available; publishing happens exclusively in
// CI (the release workflow provides the properties through the ORG_GRADLE_PROJECT_
// environment variables), so a local publish fails with a clear 'no configured signatory'
// error instead of silently producing unsigned artifacts. The private key is the ASCII
// armored key (e.g. 'gpg --armor --export-secret-keys ...'); the key id (the last 8 hex
// digits of the key fingerprint) is optional and only selects a specific subkey.
signing {
    val signingKey: String? = providers.gradleProperty("signingKey").orNull
    if (signingKey != null) {
        val signingPassword: String = providers.gradleProperty("signingPassword").getOrElse("")
        val signingKeyId: String? = providers.gradleProperty("signingKeyId").orNull
        if (signingKeyId != null) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        } else {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(publishing.publications)
    }
}
