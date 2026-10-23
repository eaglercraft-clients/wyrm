plugins {
    // A nice no-conflict comment for patching in downgrading
}

dependencies {
    api(projects.viaversionCommon)
    api(projects.viaversionBukkit)
    api(projects.viaversionVelocity)
}

tasks {
    shadowJar {
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
        archiveClassifier.set("")
        archiveFileName.set("Wyrm-${project.version}.jar")
        destinationDirectory.set(rootProject.projectDir.resolve("build/libs"))
    }
    sourcesJar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        rootProject.subprojects.forEach { subproject ->
            if (subproject == project) return@forEach
            val sourceSets = subproject.extensions.findByType(SourceSetContainer::class) ?: return@forEach
            from(sourceSets.named("main").map { it.allSource })
        }
    }
}
