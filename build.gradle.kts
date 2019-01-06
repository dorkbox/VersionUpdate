/*
 * Copyright 2018 dorkbox, llc
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
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths
import java.time.Instant
import java.util.Properties
import kotlin.reflect.full.declaredMemberProperties

plugins {
    java
    `java-gradle-plugin`

    id("com.gradle.plugin-publish") version "0.10.0"
    id("com.dorkbox.Licensing") version "1.2.2"

    kotlin("jvm") version "1.3.11"
}


println("Gradle ${project.gradle.gradleVersion}")

// load properties from custom location
val propsFile = File("$projectDir/../../gradle.properties").normalize()
if (propsFile.canRead()) {
    println("Loading custom property data from: $propsFile")

    val props = Properties()
    props.load(propsFile.inputStream())
    props.forEach{(k, v) -> project.extra.set(k as String, v as String)}
}

object Extras {
    // set for the project
    const val description = "Gradle Plugin to update version information and git tags within the Gradle project and java/kotlin files"
    const val group = "com.dorkbox"
    const val version = "1.4"

    // set as project.ext
    const val name = "Version Update"
    const val id = "VersionUpdate"
    const val vendor = "Dorkbox LLC"
    const val url = "https://git.dorkbox.com/dorkbox/VersionUpdate"
    val tags = listOf("version", "versioning", "semver", "semantic-versioning")
    val buildDate = Instant.now().toString()

    val JAVA_VERSION = JavaVersion.VERSION_1_8.toString()
}
// assign everything to project or project.ext
Extras::class.declaredMemberProperties.forEach {
    if (!project.hasProperty(it.name)) {
        when {
            it.isConst -> project.extra.set(it.name, it.getter.call())
            else -> project.extra.set(it.name, it.getter.call(Extras::class.objectInstance))
        }
    }
}

description = Extras.description
group = Extras.group
version = Extras.version


licensing {
    license(License.APACHE_2) {
        author("dorkbox, llc")
        url(Extras.url)
        note(Extras.description)
        note("Git tag code based upon 'gradle-git-version', Copyright 2015, Palantir Technologies. https://github.com/palantir/gradle-git-version")
    }
}

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
    jcenter()
}

dependencies {
    // the kotlin version is taken from the plugin, so it is not necessary to set it here
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin")
    compileOnly("org.jetbrains.kotlin:kotlin-reflect")
    compile ("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation ("org.eclipse.jgit:org.eclipse.jgit:4.5.4+")
    implementation ("com.dorkbox:Version:1.0")

    implementation ("org.slf4j:slf4j-api:1.7.25")
    runtime ("ch.qos.logback:logback-classic:1.1.6")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.isIncremental = true

    sourceCompatibility = Extras.JAVA_VERSION
    targetCompatibility = Extras.JAVA_VERSION
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = Extras.JAVA_VERSION
}


tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.FAIL

    manifest {
        // https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
        attributes["Name"] = Extras.name

        attributes["Specification-Title"] = Extras.name
        attributes["Specification-Version"] = Extras.version
        attributes["Specification-Vendor"] = Extras.vendor

        attributes["Implementation-Title"] = "${Extras.group}.${Extras.id}"
        attributes["Implementation-Version"] = Extras.buildDate
        attributes["Implementation-Vendor"] = Extras.vendor
    }
}

val wrapperUpdate by tasks.creating(Wrapper::class) {
    gradleVersion = "5.1"
    distributionUrl = distributionUrl.replace("bin", "all")
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
            tags = Extras.tags
            version = Extras.version
        }
    }
}
