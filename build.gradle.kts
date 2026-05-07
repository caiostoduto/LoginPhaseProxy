plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
    id("xyz.jpenilla.run-velocity") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val velocityVersion = "3.5.0-SNAPSHOT"

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityVersion")

    // Pick the first jar Gradle downloaded for run-velocity
    val velocityJarDir = gradle.gradleUserHomeDir.resolve("caches/run-task-jars/velocity/jars/$velocityVersion")
    val velocityJar = velocityJarDir.listFiles()?.firstOrNull { it.extension == "jar" }
        ?: error("Velocity jar not found in $velocityJarDir — run the runVelocity task once first")
    compileOnly(files(velocityJar))

    // Additional dependencies
    compileOnly("io.netty:netty-transport:4.2.10.Final")
    implementation("org.bstats:bstats-velocity:3.2.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        // Relocate bundled dependencies to avoid conflicts with other plugins
        // that might shade the same library. Replace with your group ID.
         relocate("org.bstats", project.group.toString() + ".libs.bstats")

        // Strip unnecessary files from the shadow jar
        minimize()

        // Makes this the primary jar instead of adding "-all"
        archiveClassifier.set("")
    }

    runVelocity {
        // Configure the Velocity version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        velocityVersion("3.5.0-SNAPSHOT")
    }
}

val templateSource = file("src/main/templates")
val templateDest = layout.buildDirectory.dir("generated/sources/templates")
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    val props = mapOf(
        "description" to project.description,
        "id" to project.property("id"),
        "version" to project.version,
    )
    inputs.properties(props)

    from(templateSource)
    into(templateDest)
    expand(props)
}

sourceSets.main.configure { java.srcDir(generateTemplates.map { it.outputs }) }

tasks.compileJava {
    dependsOn(generateTemplates)
}