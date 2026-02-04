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
@file:Suppress("unused")

package dorkbox.version

import dorkbox.version.tasks.*
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.*


/**
 * For automatically setting version information based on a MAJOR, MINOR, or PATCH update based on a build definition file
 */
class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register<Get>("get").configure { task ->
            task.savedProject.set(project)
            task.group = VERSION_GROUP
            task.description = "Outputs the detected version to the console"
        }

        project.tasks.register<GetDebug>("getDebug").configure { task ->
            task.savedProject.set(project)
            task.group = VERSION_GROUP
            task.description = "Outputs the detected version to the console, with additional debug information"
        }

        project.tasks.register<Tag>("tag").configure { task ->
            task.savedProject.set(project)
            task.group = VERSION_GROUP
            task.description = "Tag the current version in GIT"
        }

        project.tasks.register<Major>("incrementMajor").configure { task ->
            task.savedProject.set(project)
            task.group = VERSION_GROUP
            task.description = "Increments the MAJOR version by 1, and resets MINOR/PATCH to 0"
        }

        project.tasks.register<Minor>("incrementMinor").configure { task ->
            task.savedProject.set(project)
            task.group = VERSION_GROUP
            task.description = "Increments the MINOR version by 1, and resets PATCH to 0"
        }

        project.tasks.register<Patch>("incrementPatch").configure { task ->
            task.savedProject.set(project)
            task.group = VERSION_GROUP
            task.description = "Increments the PATCH version by 1"
        }

        project.afterEvaluate {
            project.plugins.findPlugin("com.dorkbox.GradlePublish")?.let { plugin ->
                println("\tAutomatic version tagging of git state after MavenCentral release")

                project.tasks.named("publishToMavenAndRelease") {
                    it.finalizedBy("tag")
                }
            }

            // just make sure that we have a version defined.
            val version = project.version.toString()

            if (version.isBlank() || version == Project.DEFAULT_VERSION) {
                // no version info specified, but version task was called
                println("\tProject ${project.name} version information is unset. Please set via `project.version = '1.0'`")
            }
        }
    }


    companion object {
        const val VERSION_GROUP = "version"

        // NOTE: These ignore spaces!

        // version
        private val buildText = """version""".toRegex()

        // String getVersion() {
        private val javaMethod = """String getVersion\(\)\s*\{""".toRegex()

        // fun getVersion() : String {
        private val kotlinMethod = """fun getVersion\(\)\s*:\s*String\s*\{""".toRegex()

        // return "...."
        private val methodReturnString = """(return ")(.*)(")""".toRegex()



        // String version =
        private val javaString = """.*String version\s*=""".toRegex()

        // const val version =
        private val kotlinString = """.*val version\s*=""".toRegex()

        // Uses a positive lookahead to ensure at least one digit exists, then matches word characters, dots, plus, or minus
        private val string2VersionString = """(version\s*=\s*)"((?=.*\d)[\w.+-]*)(")""".toRegex()



        // NOT VALID
        // id("com.dorkbox.Licensing") version "2.5.2"

        // VALID (because of different ways to assign values, we want to be explicit)
        // version = "1.0.0"
        // const val version = '1.0.0'
        // project.version = "1.0.0"
        private val buildFileVersionString = """^\s*\b(?:const val version|project\.version|version)\b\s*=\s*(['"])((?=.*\d)[\w.+-]*)(['"])$""".toRegex()


        /*
            Maven Info
            ---------
            ```
            <dependencies>
                ...
                <dependency>
                    <groupId>com.dorkbox</groupId>
                    <artifactId>SystemTray</artifactId>
                    <version>3.14</version>
                </dependency>
            </dependencies>
            ```


            Gradle Info
            ---------
            ```
            dependencies {
                ...
                compile 'com.dorkbox:SystemTray:3.14'
            }
            ```
         */
        private const val readmeMavenInfoString = """Maven Info"""
        private const val readmeGradleInfoString = """Gradle Info"""
        private const val readmeTicksString = """```""" // only 3 ticks required

        /*
            Maven Info
            ---------
            ```
            <dependencies>
                ...
                <dependency>
                    <groupId>com.dorkbox</groupId>
                    <artifactId>SystemTray</artifactId>
                    <version>3.14</version>
                </dependency>
            </dependencies>
            ```
         */
        private val readmeMavenString = """(<version>)(.*)(</version>)""".toRegex()



        /*
            Gradle Info
            ---------
            ```
            dependencies {
                ...
                compile 'com.dorkbox:SystemTray:3.14'
            }
            ```
         */
        // note: this can be the ONLY version info present, otherwise there will be problems!
        private val readmeGradleString = """.*(['"].*:.*:)(.*)(['"])""".toRegex()


        data class VerData(val file: File, val line: Int, val version: String, val lineReplacement: String)

        /**
         * Gets the version info from the project
         */
        fun getVersion(project: Project): Version {
            // get the version info from project.version
            val version = project.version.toString()

            if (version.isBlank() || version == Project.DEFAULT_VERSION) {
                // no version info specified, but version task was called
                throw GradleException("Project version information is unset.")
            }

            return Version(version)
        }

        fun saveNewVersionInfo(project: Project, oldVersion: Version, newVersion: Version) {
            // Verifies that all the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, oldVersion, newVersion)

            // now save the NEW version to all the files (this also has our build + README files)
            filesWithVersionInfo.forEach { data ->
                var lineNumber = 1  // visual editors start at 1, so we should too
                val tempFile = kotlin.io.path.createTempFile("tmp", "data.file").toFile()

                // NOTE: we want to REUSE whatever line-encoding is used in the file. We assume that the entire file is consistent
                // CRLF, LF, CR
                // '\n' and '\r'
                //
                // we read the first TWO lines to determine this.

                val cr = '\r'.code.toByte().toInt()
                val lf = '\n'.code.toByte().toInt()

                var count = 0
                val bufferSize = DEFAULT_BUFFER_SIZE-1

                var hasCR = false
                var hasLF = false
                val reader = data.file.bufferedReader(Charsets.UTF_8)
                reader.mark(bufferSize)

                while (count++ < bufferSize) {
                    val char = reader.read()
                    if (char == cr) {
                        if (hasCR) break // if we ALREADY read this line ending, abort because we have read enough to determine everything
                        hasCR = true
                    } else if (char == lf) {
                        if (hasLF) break // if we ALREADY read this line ending, abort because we have read enough to determine everything
                        hasLF = true
                    }
                }
                reader.reset()

                val NL = when {
                    hasCR && hasLF -> "\r\n"
                    hasCR  -> "\r"
                    else   -> "\n"
                }

                tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    reader.use {
                        it.lineSequence().forEach { line ->
                            if (lineNumber == data.line) {
                                writer.write(data.lineReplacement)
                                writer.write(NL)
                                println("\tUpdating file '${data.file}' to version $newVersion at line $lineNumber")
                            }
                            else {
                                writer.write(line)
                                writer.write(NL)
                            }

                            writer.flush()
                            lineNumber++
                        }
                    }
                }

                check(data.file.delete() && tempFile.renameTo(data.file)) { "Failed to replace file ${data.file}" }
            }

            // now make sure the files were actually updated
            val updatedFilesWithVersionInfo = verifyVersion(project, newVersion, newVersion)

            val oldFiles = filesWithVersionInfo.map { verData -> verData.file }.toMutableList()
            val newFiles = updatedFilesWithVersionInfo.map { verData -> verData.file }

            oldFiles.removeAll(newFiles)

            if (oldFiles.isNotEmpty()) {
                throw GradleException("Version information in files $oldFiles were not successfully updated.")
            }
        }

        private fun methodMatch(debug: Boolean, regex: Regex, line: String, lineNumber: Int, file: File): Boolean {
            if (line.contains(regex)) {
                if (line.startsWith("//")) {
                    if (debug) {
                        println("\t\tFound line starting with comment prefix text: [$regex] ($line)")
                        println("\t\t$file @ $lineNumber\n")
                    }
                } else {
                    if (debug) {
                        println("\t\tFound matching prefix text: [$regex] ($line)")
                        println("\t\t$file @ $lineNumber\n")
                    }

                    // true so the text is matched on the following line
                    return true
                }
            }
            return false
        }

        typealias FoundFunc = (String, String, File, Int) -> Unit

        private fun lineMatch(debug: Boolean, regex: Regex, trimmedLine: String, line: String, lineNumber: Int, file: File, foundFunc: FoundFunc): Boolean {
            val matchResult = regex.find(trimmedLine)
            if (matchResult != null) {
                if (trimmedLine.startsWith("//")) {
                    if (debug) {
                        println("\t Found line starting with comment prefix text: [$regex] ($trimmedLine)")
                        println("\t\t$file @ $lineNumber\n")
                    }
                    return false
                }

                if (debug) {
                    println("\t Found matching same-line text: [$regex] ($trimmedLine)")
                    println("\t\t$file @ $lineNumber\n")
                }

                try {
                    val (_, ver, _) = matchResult.destructured
                    foundFunc(line, ver, file, lineNumber)
                    return true
                } catch (e: Exception) {
                    println("\t Error parsing text: [$regex] ($trimmedLine)")
                    println("\t\t$file @ $lineNumber\n")
                    e.printStackTrace()
                }
            }
            return false
        }



        /**
         * Verifies that all the project files are set to the specified version
         */
        @Suppress("DuplicatedCode")
        fun verifyVersion(project: Project, oldVersion: Version, newVersion: Version, debug: Boolean = false): List<VerData> {
            val alreadyParsedFiles = getSourceFiles(project)
            val filesWithVersionInfo = mutableListOf<VerData>()

            if (debug) {
                println("\tExpecting version info: old:$oldVersion, new:$newVersion")
                println("\tChecking ${alreadyParsedFiles.size} code files")
            }

            val foundFunc: FoundFunc = { line, foundVer, file, lineNumber ->
                if (foundVer == oldVersion.toString()) {
                    val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                    filesWithVersionInfo.add(VerData(file, lineNumber, oldVersion.toString(), lineReplacement))
                } else {
                    println("\t Version mismatch $foundVer != $oldVersion")
                    println("\t - $file @ $lineNumber\n")
                }
            }

            // collect all the class files that have a version defined (look in source sets. must match our pre-defined pattern)
            alreadyParsedFiles
                .filter { it.extension == "java" ||  it.extension == "kt"}
                .forEach { file ->
                    val stringRegex: Regex
                    val methodRegex: Regex

                    if (file.extension == "java") {
                        stringRegex = javaString
                        methodRegex = javaMethod
                    } else {
                        stringRegex = kotlinString
                        methodRegex = kotlinMethod
                    }

                run fileCheck@{
                    var matchText = false
                    var lineNumber = 1  // visual editors start at 1, so we should too



                    file.useLines { lines ->
                        lines.forEach { line ->
                            val trimmed = line.trim()
                            if (methodMatch(debug, methodRegex, trimmed, lineNumber, file)) {
                                // this is so the text is matched on the following line
                                matchText = true
                            } else if (line.contains(stringRegex) && lineMatch(debug, string2VersionString, trimmed, line, lineNumber, file, foundFunc)) {
                                return@fileCheck
                            }
                            else if (matchText && lineMatch(debug, methodReturnString, trimmed, line, lineNumber, file, foundFunc)) {
                                return@fileCheck
                            }

                            lineNumber++
                        }
                    }
                }
            }

            if (debug) {
                println("\n\tChecking build file")
            }

            // get version info by file parsing from gradle.build file
            project.buildFile.useLines { lines ->
                var lineNumber = 1  // visual editors start at 1, so we should too

                lines.forEach { line ->
                    val trimmed = line.trim()

                    if (line.contains(buildText) && lineMatch(debug, buildFileVersionString, trimmed, line, lineNumber, project.buildFile, foundFunc)) {
                        return@useLines
                    }

                    lineNumber++
                }
            }

            // get version info by parsing the README.MD file, if it exists (OPTIONAL)
            // this file will always exist next to the build file. We should ignore case (because yay windows!)
            var readmeFile: File? = null
            val listFiles = project.buildFile.parentFile.listFiles()
            listFiles?.forEach {
                if (it.name.lowercase(Locale.getDefault()) == "readme.md") {
                    readmeFile = it
                    return@forEach
                }
            }

            if (debug) {
                if (readmeFile != null) {
                    println("\tFound readme file: $readmeFile")
                } else if (listFiles == null) {
                    println("\tNO FILES FOUND!!")
                }
            }

            // it won't always exist, but if it does, process it as well
            // TWO version entries possible. One for MAVEN and one for GRADLE
            if (readmeFile != null && readmeFile.canRead()) {
                val readme = readmeFile

                readme.useLines { lines ->
                    var lineNumber = 1  // visual editors start at 1, so we should too

                    // only 1 instance of maven/gradle can be found!
                    var enableMaven = true
                    var enableGradle = true

                    var foundSectionTicks = false
                    var foundMaven = false
                    var foundGradle = false

                    // file has MAVEN info first, followed by GRADLE info
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        when {
                            enableMaven && !foundMaven && trimmed == readmeMavenInfoString                    -> {
                                foundMaven = true
                                if (debug) {
                                    println("\t\tFound maven ($lineNumber): $readmeMavenInfoString")
                                }
                            }
                            enableMaven && !foundSectionTicks && foundMaven && trimmed == readmeTicksString   -> {
                                foundSectionTicks = true
                                if (debug) {
                                    println("\t\tFound maven ticks ($lineNumber): $readmeTicksString")
                                }
                            }
                            enableMaven && foundSectionTicks && trimmed == readmeTicksString                  -> {
                                enableMaven = false
                                foundMaven = false
                                foundSectionTicks = false
                                if (debug) {
                                    println("\t\tEnd maven ticks ($lineNumber)")
                                }
                            }


                            enableGradle && !foundGradle && trimmed == readmeGradleInfoString                 -> {
                                foundGradle = true
                                if (debug) {
                                    println("\t\tFound gradle ($lineNumber): $readmeGradleInfoString")
                                }
                            }
                            enableGradle && !foundSectionTicks && foundGradle && trimmed == readmeTicksString -> {
                                foundSectionTicks = true
                                if (debug) {
                                    println("\t\tFound gradle ticks ($lineNumber): $readmeTicksString")
                                }
                            }
                            enableGradle && foundSectionTicks && trimmed == readmeTicksString                 -> {
                                enableGradle = false
                                foundGradle = false
                                foundSectionTicks = false
                                if (debug) {
                                    println("\t\tEnd gradle ticks ($lineNumber)")
                                }
                            }

                            // block that maven stuff is in
                            foundMaven && foundSectionTicks -> {
                                if (debug) {
                                    println("\t\tSearching maven ($lineNumber): '$readmeMavenString' --> '$line'")
                                }

                                val matchResult = readmeMavenString.find(line)
                                if (matchResult != null) {
                                    val (_, ver, _) = matchResult.destructured
                                    if (debug) {
                                        println("\t\t\tmatched maven info ($lineNumber): $ver" )
                                    }


                                    // verify it's what we think it is
                                    if (ver == oldVersion.toString()) {
                                        val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                        filesWithVersionInfo.add(VerData(readme, lineNumber, ver, lineReplacement))

                                        foundMaven = false

                                        if (debug) {
                                            println("\t\t\tmatched maven version! ($lineNumber): $ver")
                                        }
                                        // return@useLines // keep going, since we have to look for gradle info too
                                    } else {
                                        if (debug) {
                                            print("\t\t")
                                        }
                                        println("\tMaven version mismatch in $readmeFile! $ver != $oldVersion (line: $lineNumber)")
                                    }
                                }
                            }

                            // block that gradle stuff is in
                            foundGradle && foundSectionTicks -> {
                                if (debug) {
                                    println("\t\tSearching gradle ($lineNumber): '$readmeGradleString' --> '$line'")
                                }

                                val matchResult = readmeGradleString.find(line)
                                if (matchResult != null) {
                                    val (_, ver, _) = matchResult.destructured
                                    if (debug) {
                                        println("\t\t\tmatched gradle info ($lineNumber): $ver" )
                                    }


                                    // verify it's what we think it is
                                    if (ver == oldVersion.toString()) {
                                        val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                        filesWithVersionInfo.add(VerData(readme, lineNumber, ver, lineReplacement))

                                        foundGradle = false

                                        if (debug) {
                                            println("\t\t\tmatched gradle version! ($lineNumber): $ver")
                                        }
                                        // return@useLines // keep going, since we have to look for maven info too (in case order is reversed)
                                    } else {
                                        if (debug) {
                                            print("\t\t")
                                        }
                                        println("\tGradle version mismatch in $readmeFile! $ver != $oldVersion (line: $lineNumber)")
                                    }
                                }
                            }
                        }

                        lineNumber++
                    }
                }
            }


            // make sure version info all match (throw error and exit if they do not)
            filesWithVersionInfo.forEach { info ->
                if (debug) {
                     println("\t Verifying file '${info.file}' for version '${info.version} at line ${info.line}'")
                }
                if (Version(info.version) != oldVersion) {
                    throw GradleException("Version information mismatch, expected $oldVersion, got ${info.version} in file: ${info.file} at line ${info.line}")
                }
            }

            return filesWithVersionInfo.toList()
        }

        private fun getSourceFiles(project: Project): Set<File> {
            val alreadyParsedFiles = mutableSetOf<File>()

            // check to see if we have any kotlin file
            val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer
            val main = sourceSets.getByName("main")

            main.java.files.forEach { file ->
                alreadyParsedFiles.add(file)
            }

            try {
                val kotlin = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets.getByName("main").kotlin

                kotlin.files.forEach { file ->
                    alreadyParsedFiles.add(file)
                }
            } catch (_: Exception) {
                //ignored. kotlin might not exist
            }

            return alreadyParsedFiles
        }
    }
}
