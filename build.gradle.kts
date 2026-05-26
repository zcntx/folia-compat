plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.foliacompat"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
}

tasks {
    jar {
        manifest {
            attributes(
                "Premain-Class" to "com.foliacompat.agent.FoliaCompatAgent",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true",
                "Can-Set-Native-Method-Prefix" to "true"
            )
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("org.objectweb.asm", "com.foliacompat.libs.asm")
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}
