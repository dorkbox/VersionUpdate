/*
 * Copyright 2020 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.time.Instant

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!
gradle.startParameter.warningMode = WarningMode.All

plugins {
    java
    `java-gradle-plugin`

    id("com.gradle.plugin-publish") version "0.14.0"
    id("com.dorkbox.GradleUtils") version "2.8"

    kotlin("jvm") version "1.5.21"
}

object Extras {
    // set for the project
    const val description = "Gradle Plugin to update version information and git tags within the Gradle project and java/kotlin files"
    const val group = "com.dorkbox"
    const val version = "2.4"

    // set as project.ext
    const val name = "Version Update"
    const val id = "VersionUpdate"
    const val vendor = "Dorkbox LLC"
    const val vendorUrl = "https://dorkbox.com"
    const val url = "https://git.dorkbox.com/dorkbox/VersionUpdate"

    val buildDate = Instant.now().toString()
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.fixIntellijPaths()
GradleUtils.defaultResolutionStrategy()
GradleUtils.compileConfiguration(JavaVersion.VERSION_1_8)

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))

            // want to include kotlin files for the source. 'setSrcDirs' resets includes...
            include("**/*.kt")
        }
    }
}

repositories {
    mavenLocal() // this must be first!
    mavenCentral()
}

dependencies {
    // the kotlin version is taken from the plugin, so it is not necessary to set it here
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin")

    implementation("org.eclipse.jgit:org.eclipse.jgit:5+")
    implementation("com.dorkbox:Version:2.4")
}

tasks.jar.get().apply {
    manifest {
        // https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
        attributes["Name"] = Extras.name

        attributes["Specification-Title"] = Extras.name
        attributes["Specification-Version"] = Extras.version
        attributes["Specification-Vendor"] = Extras.vendor

        attributes["Implementation-Title"] = "${Extras.group}.${Extras.id}"
        attributes["Implementation-Version"] = Extras.buildDate
        attributes["Implementation-Vendor"] = Extras.vendor

        attributes["Automatic-Module-Name"] = Extras.id
    }
}


/////////////////////////////////
////////    Plugin Publishing + Release
/////////////////////////////////
gradlePlugin {
    plugins {
        create("Version") {
            id = "${Extras.group}.${Extras.id}"
            implementationClass = "dorkbox.version.VersionPlugin"
        }
    }
}

pluginBundle {
    website = Extras.url
    vcsUrl = Extras.url

    (plugins) {
        "Version" {
            id = "${Extras.group}.${Extras.id}"
            displayName = Extras.name
            description = Extras.description
            tags = listOf("version", "versioning", "semver", "semantic-versioning")
            version = Extras.version
        }
    }
}
