/*
 * Copyright 2026 dorkbox, llc
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

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!
gradle.startParameter.warningMode = WarningMode.All

plugins {
    java
    `java-gradle-plugin`

    id("com.gradle.plugin-publish") version "2.0.0"

    id("com.dorkbox.GradleUtils") version "4.8"
    id("com.dorkbox.Licensing") version "3.1"
    id("com.dorkbox.VersionUpdate") version "3.1"

    kotlin("jvm") version "2.3.0"
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load {
    group = "com.dorkbox"
    id = "VersionUpdate"
    description = "Gradle Plugin to update version information and git tags within the Gradle project and java/kotlin files"
    name = "Version Update"
    version = "3.1"
    vendor = "Dorkbox LLC"
    url = "https://git.dorkbox.com/dorkbox/VersionUpdate"
}
GradleUtils.defaults()
GradleUtils.compileConfiguration(JavaVersion.VERSION_25)

licensing {
    license(License.APACHE_2) {
        description(Extras.description)
        author(Extras.vendor)
        url(Extras.url)
    }
}

dependencies {
    api(gradleApi())
    api(gradleKotlinDsl())

    // the kotlin version is taken from the plugin, so it is not necessary to set it here
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.5.0.202512021534-r")
    implementation("com.dorkbox:Version:3.2")
}


/////////////////////////////////
////////    Plugin Publishing + Release
/////////////////////////////////
gradlePlugin {
    website.set(Extras.url)
    vcsUrl.set(Extras.url)

    plugins {
        register("Version") {
            id = Extras.groupAndId
            implementationClass = "dorkbox.version.VersionPlugin"
            displayName = Extras.name
            description = Extras.description
            version = Extras.version
            tags.set(listOf("version", "versioning", "semver", "semantic-versioning"))
        }
    }
}
