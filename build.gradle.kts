plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.easyrequest"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://www.jetbrains.com/intellij-repository/releases")
    intellijPlatform { defaultRepositories() }
}

// Compile production code against the oldest supported IDE. The newer SDK is used for tests.
val legacySdk by configurations.creating { isTransitive = false }
val legacyIdePath = providers.gradleProperty("legacyIdePath")
val legacyIdeHome = legacyIdePath.map { file(it) }
    .orElse(layout.buildDirectory.dir("compatibility/idea-2019.1.4").map { it.asFile })
val unpackLegacySdk by tasks.registering(Sync::class) {
    from(provider { zipTree(legacySdk.singleFile) })
    into(layout.buildDirectory.dir("compatibility/idea-2019.1.4"))
}

dependencies {
    add(legacySdk.name, "com.jetbrains.intellij.idea:ideaIC:2019.1.4@zip")
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdeaCommunity("2025.1.2")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.5.1")
    runtimeOnly("org.slf4j:slf4j-jdk14:1.7.36")
    runtimeOnly("org.brotli:dec:0.1.2")
    runtimeOnly("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "191"
            untilBuild = "263.*"
        }
    }
    instrumentCode = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = if (name == "compileJava") 8 else 21
}

tasks.compileJava {
    if (!legacyIdePath.isPresent) dependsOn(unpackLegacySdk)
    classpath = configurations.runtimeClasspath.get() + fileTree(legacyIdeHome) {
        include("lib/*.jar", "plugins/java/lib/*.jar")
    }
}

tasks.test {
    useJUnit()
    maxHeapSize = "1536m"
    systemProperty("java.awt.headless", "true")
}

tasks.shadowJar {
    // Keep the Java 8 implementations; mixing multi-release entries breaks shaded SnakeYAML.
    exclude("META-INF/versions/**", "module-info.class")
    mergeServiceFiles()
    relocate("com.google.gson", "com.easyrequest.internal.gson")
    relocate("org.yaml.snakeyaml", "com.easyrequest.internal.snakeyaml")
    relocate("org.apache.hc", "com.easyrequest.internal.httpcomponents")
    relocate("org.slf4j", "com.easyrequest.internal.slf4j")
    relocate("org.brotli", "com.easyrequest.internal.brotli")
}

// Package isolated libraries so old IDE-bundled Gson/YAML/HTTP versions cannot override them.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    pluginJar.set(tasks.shadowJar.flatMap { it.archiveFile })
    runtimeClasspath.setFrom(files())
}

val compileCompatibilitySmoke by tasks.registering(JavaCompile::class) {
    source(fileTree("src/compatTest/java") { include("**/*.java") })
    classpath = files(tasks.shadowJar.flatMap { it.archiveFile })
    destinationDirectory.set(layout.buildDirectory.dir("classes/compatibilitySmoke"))
    options.release.set(8)
}

tasks.register<Exec>("verifyJava8Runtime") {
    group = "verification"
    description = "Check the distributed JAR on Java 8; supply -Pjava8Home=/path/to/jdk8."
    dependsOn(compileCompatibilitySmoke)
    val smokeClasspath = files(compileCompatibilitySmoke.flatMap { it.destinationDirectory }, tasks.shadowJar.flatMap { it.archiveFile })
    doFirst {
        val home = providers.gradleProperty("java8Home").orNull
            ?: error("Supply -Pjava8Home=/path/to/jdk8 to verify the oldest supported runtime.")
        commandLine(file("$home/bin/java").absolutePath, "-cp", smokeClasspath.asPath, "com.easyrequest.compat.PackagedPluginSmoke")
    }
}
