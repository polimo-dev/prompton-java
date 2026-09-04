plugins {
    `java-library`
    `maven-publish`
}

group = "dev.polimo"
version = "0.1.0"
description = "PromptOn SDK for Java — resolve pinned prompts and models locally, render them, and batch monitoring logs."

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf("-Xlint:all,-serial,-this-escape,-processing", "-Werror")
    )
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    listOf("PTN_HOST", "PTN_API_KEY", "PTN_PROJECT").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}

tasks.register<JavaExec>("example") {
    group = "application"
    description = "Runs the runnable example in examples/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.polimo.prompton.examples.QuickStart"
}

sourceSets {
    test {
        java.srcDir("examples")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "prompton-sdk"
            from(components["java"])
            pom {
                name = "prompton-sdk"
                description = project.description
                url = "https://github.com/polimo-dev/prompton-java"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}
