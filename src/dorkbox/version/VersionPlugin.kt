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
package dorkbox.version

import com.dorkbox.version.Version
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.io.path.ExperimentalPathApi


/**
 * For automatically setting version information based on a MAJOR, MINOR, or PATCH update based on a build definition file
 */
class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.tasks.create("get", Get::class.java).apply {
            group = "version"
            description = "Outputs the detected version to the console"
        }
        project.tasks.create("get-debug", GetDebug::class.java).apply {
            group = "version"
            description = "Outputs the detected version to the console, with additional debug information"
        }
        project.tasks.create("tag", IncrementTasks.Tag::class.java).apply {
            group = "version"
            description = "Tag the current version in GIT"
        }
        project.tasks.create("incrementMajor", IncrementTasks.Major::class.java).apply {
            group = "version"
            description = "Increments the MAJOR version by 1, and resets MINOR/PATCH to 0"
        }
        project.tasks.create("incrementMinor", IncrementTasks.Minor::class.java).apply {
            group = "version"
            description = "Increments the MINOR version by 1, and resets PATCH to 0"
        }
        project.tasks.create("incrementPatch", IncrementTasks.Patch::class.java).apply {
            group = "version"
            description = "Increments the PATCH version by 1"
        }

        project.afterEvaluate {
            // just make sure that we have a version defined.
            val version = project.version.toString()

            if (version.isBlank() || version == Project.DEFAULT_VERSION) {
                // no version info specified, but version task was called
                println("\tProject ${project.name} version information is unset. Please set via `project.version = '1.0'`")
            }
        }
    }


    companion object {
        // NOTE: These ignore spaces!

        // version
        private val buildText = """version""".toRegex()

        // String getVersion() {
        private val javaText = """String getVersion\(\)\s*\{""".toRegex()

        // fun getVersion() : String {
        private val kotlinText = """fun getVersion\(\)\s*:\s*String\s*\{""".toRegex()

        // const val version =
        private val kotlinText2 = """.*val version\s*=""".toRegex()
        private val kotlinText2VersionText = """(version\s*=\s*")(.*)(")""".toRegex()

        // return "...."
        private val versionText = """(return ")(.*)(")""".toRegex()


        // NOT VALID
        // id("com.dorkbox.Licensing") version "2.5.2"

        // VALID (because of different ways to assign values, we want to be explicit)
        // version = "1.0.0"
        // const val version = '1.0.0'
        // project.version = "1.0.0"
        private val buildFileVersionText = """^(?:\s)*\b(?:const val version|project\.version|version)\b(?:\s*=\s*)(?:'|")(\d.+)(?:'|")$""".toRegex()


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
        private const val readmeMavenInfoText = """Maven Info"""
        private const val readmeGradleInfoText = """Gradle Info"""
        private const val readmeTicksText = """```""" // only 3 ticks required

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
        private val readmeMavenText = """(<version>)(.*)(</version>)""".toRegex()



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
        private val readmeGradleText = """.*(['"].*:.*:)(.*)(['"])""".toRegex()


        data class VerData(val file: File, val line: Int, val version: String, val lineReplacement: String)

        /**
         * Get's the version info from the project
         */
        fun getVersion(project: Project): Version {
            // get the version info from project.version
            val version = project.version.toString()

            if (version.isBlank() || version == Project.DEFAULT_VERSION) {
                // no version info specified, but version task was called
                throw GradleException("Project version information is unset.")
            }

            return Version.from(version)
        }


        @ExperimentalPathApi
        fun saveNewVersionInfo(project: Project, oldVersion: Version, newVersion: Version) {
            // Verifies that all of the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, oldVersion, newVersion)

            // now save the NEW version to all of the files (this also has our build + README files)
            filesWithVersionInfo.forEach { data ->
                var lineNumber = 1  // visual editors start at 1, so we should too
                val tempFile = kotlin.io.path.createTempFile("tmp", "data.file").toFile()

                tempFile.printWriter().use { writer ->
                    data.file.useLines { lines ->
                        lines.forEach { line ->
                            if (lineNumber == data.line) {
                                writer.println(data.lineReplacement)
                                println("\tUpdating file '${data.file}' to version $newVersion at line $lineNumber")
                            }
                            else {
                                writer.println(line)
                            }
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

        fun createTag(project: Project, newVersion: Version) {
            // make sure all code is committed (no un-committed files and no staged files). Throw error and exit if there is
            val git = getGit(project)
            val status = git.status().call()

            if (status.hasUncommittedChanges()) {
                println("The following files are uncommitted: ${status.uncommittedChanges}")
            }

            // make sure there are no git tags with the current tag name
            val tagName = "Version_$newVersion"

            // do we already have this tag?
            val call = git.tagList().call()
            for (ref in call) {
                if (ref.name == tagName) {
                    throw GradleException("Tag $tagName already exists. Please delete the old tag in order to continue.")
                }
            }

            // Verifies that all of the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, newVersion, newVersion)
            val files = filesWithVersionInfo.map { verData -> verData.file }

            // must include the separator.
            val projectPath = project.buildFile.parentFile.normalize().absolutePath + File.separator

            files.forEach {
                // now add the file to git. MUST BE repository-relative path!
                val filePath = it.normalize().absolutePath.replace(projectPath, "")
                git.add().addFilepattern(filePath).call()
            }

            // now create the git tag
            git.tag().setName(tagName).call()

            println("Successfully created git tag $tagName" )
        }

        /**
         * Verifies that all of the project files are set to the specified version
         */
        @Suppress("DuplicatedCode")
        private fun verifyVersion(project: Project, oldVersion: Version, newVersion: Version, debug: Boolean = false): List<VerData> {
            val alreadyParsedFiles = getSourceFiles(project)
            val filesWithVersionInfo = ArrayList<VerData>()

            if (debug) {
                println("Expecting version info: old=$oldVersion, new=$newVersion")
                println("Checking code files: $javaText")
                println("Checking code files: $kotlinText")
                println("Checking code files: $kotlinText2")
            }

            // collect all of the class files that have a version defined (look in source sets. must match our pre-defined pattern)
            alreadyParsedFiles.forEach { file ->
                if (debug) {
                    println("\t$file")
                }

                run fileCheck@{
                    var matchesText = false
                    var lineNumber = 1  // visual editors start at 1, so we should too

                    if (file.extension == "java") {
                        file.useLines { lines ->
                            lines.forEach { line ->
                                if (line.contains(javaText)) {
                                    if (debug) {
                                        println("\t\tFound matching JAVA prefix text ($lineNumber): $javaText")
                                    }

                                    // this is so the text is matched on the following line
                                    matchesText = true
                                }
                                else if (matchesText) {
                                    val matchResult = versionText.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        if (ver == oldVersion.toString()) {
                                            val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                            filesWithVersionInfo.add(VerData(file, lineNumber, ver, lineReplacement))
                                        } else {
                                            println("\tVersion mismatch in $file! $ver != $oldVersion (line: $lineNumber)")
                                        }
                                        return@fileCheck
                                    }
                                }
                                lineNumber++
                            }
                        }
                    }
                    else if (file.extension == "kt") {
                        file.useLines { lines ->
                            lines.forEach { line ->
                                if (line.contains(kotlinText)) {
                                    if (debug) {
                                        println("\t\tFound matching KOTLIN prefix text ($lineNumber): $kotlinText")
                                    }

                                    // this is so the text is matched on the following line
                                    matchesText = true
                                } else if (line.contains(kotlinText2)) {
                                    if (debug) {
                                        println("\t\tFound matching KOTLIN same-line text ($lineNumber): $kotlinText2")
                                    }

                                    // same line
                                    val matchResult = kotlinText2VersionText.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        if (ver == oldVersion.toString()) {
                                            val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                            filesWithVersionInfo.add(VerData(file, lineNumber, ver, lineReplacement))
                                        } else {
                                            println("\tVersion mismatch in $file! $ver != $oldVersion (line: $lineNumber)")
                                        }
                                        return@fileCheck
                                    }
                                } else if (matchesText) {
                                    val matchResult = versionText.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        if (ver == oldVersion.toString()) {
                                            val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                            filesWithVersionInfo.add(VerData(file, lineNumber, ver, lineReplacement))
                                        } else {
                                            println("\tVersion mismatch in $file! $ver != $oldVersion (line: $lineNumber)")
                                        }
                                        return@fileCheck
                                    }
                                }
                                lineNumber++
                            }
                        }
                    }
                }
            }

            if (debug) {
                println("Checking build file for: $kotlinText")
                println("\t${project.buildFile}")
            }

            // get version info by file parsing from gradle.build file
            project.buildFile.useLines { lines ->
                var lineNumber = 1  // visual editors start at 1, so we should too

                lines.forEach { line ->
                    if (line.contains(buildText)) {
                        if (debug) {
                            println("\t\tFound matching build same-line text ($lineNumber): $buildText")
                        }

                        val matchResult = buildFileVersionText.find(line)
                        if (matchResult != null) {
                            val (ver) = matchResult.destructured
                            // verify it's what we think it is
                            if (ver == oldVersion.toString()) {
                                val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                filesWithVersionInfo.add(VerData(project.buildFile, lineNumber, ver, lineReplacement))
                                return@useLines
                            } else {
                                println("\tVersion mismatch in ${project.buildFile}! $ver != $oldVersion (line: $lineNumber)")
                            }
                        }
                    }

                    lineNumber++
                }
            }

            // get version info by parsing the README.MD file, if it exists (OPTIONAL)
            // this file will always exist next to the build file. We should ignore case (because yay windows!)
            var readmeFile: File? = null
            val listFiles = project.buildFile.parentFile.listFiles()
            listFiles?.forEach {
                if (it.name.toLowerCase() == "readme.md") {
                    readmeFile = it
                    return@forEach
                }
            }

            if (debug) {
                if (readmeFile != null) {
                    println("Found readme file: $readmeFile")
                } else {
                    if (listFiles == null) {
                        println("\tNO FILES FOUND!!")
                    } else {
                        listFiles.forEach {
                            println("\t$it")
                        }
                    }
                }
            }

            // it won't always exist, but if it does, process it as well
            // TWO version entries possible. One for MAVEN and one for GRADLE
            if (readmeFile != null && readmeFile!!.canRead()) {
                val readme = readmeFile!!

                readme.useLines { lines ->
                    var lineNumber = 1  // visual editors start at 1, so we should too

                    // only 1 instance of maven/gradle can be found!
                    var enableMaven = true
                    var enableGradle = true

                    var foundSectionTicks = false
                    var foundMaven = false
                    var foundGradle = false

                    // file has MAVEN info first, followed by GRADLE info
                    lines.forEach {
                        val line = it.trim()

                        when {
                            enableMaven && !foundMaven && line == readmeMavenInfoText -> {
                                foundMaven = true
                                if (debug) {
                                    println("\t\tFound maven ($lineNumber): $readmeMavenInfoText")
                                }
                            }
                            enableMaven && !foundSectionTicks && foundMaven && line == readmeTicksText -> {
                                foundSectionTicks = true
                                if (debug) {
                                    println("\t\tFound maven ticks ($lineNumber): $readmeTicksText")
                                }
                            }
                            enableMaven && foundSectionTicks && line == readmeTicksText -> {
                                enableMaven = false
                                foundMaven = false
                                foundSectionTicks = false
                                if (debug) {
                                    println("\t\tEnd maven ticks ($lineNumber)")
                                }
                            }


                            enableGradle && !foundGradle && line == readmeGradleInfoText -> {
                                foundGradle = true
                                if (debug) {
                                    println("\t\tFound gradle ($lineNumber): $readmeGradleInfoText")
                                }
                            }
                            enableGradle && !foundSectionTicks && foundGradle && line == readmeTicksText -> {
                                foundSectionTicks = true
                                if (debug) {
                                    println("\t\tFound gradle ticks ($lineNumber): $readmeTicksText")
                                }
                            }
                            enableGradle && foundSectionTicks && line == readmeTicksText -> {
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
                                    println("\t\tSearching maven ($lineNumber): '$readmeMavenText' --> '$line'")
                                }

                                val matchResult = readmeMavenText.find(line)
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
                                    println("\t\tSearching gradle ($lineNumber): '$readmeGradleText' --> '$line'")
                                }

                                val matchResult = readmeGradleText.find(line)
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
                     println("Verifying file '${info.file}' for version '${info.version} at line ${info.line}'")
                }
                if (Version.from(info.version) != oldVersion) {
                    throw GradleException("Version information mismatch, expected $oldVersion, got ${info.version} in file: ${info.file} at line ${info.line}")
                }
            }

            return filesWithVersionInfo.toList()
        }

        private fun getSourceFiles(project: Project): HashSet<File> {
            val alreadyParsedFiles = HashSet<File>()

            project.convention.getPlugin(JavaPluginConvention::class.java).sourceSets.all { sourceSet ->
                sourceSet.java { directorySet ->
                    directorySet.forEach { file ->
                        alreadyParsedFiles.add(file)
                    }
                }

                try {
                    val set = (sourceSet as org.gradle.api.internal.HasConvention).convention.getPlugin(KotlinSourceSet::class.java)
                    val kot = set.kotlin
                    kot.files.forEach { file ->
                        alreadyParsedFiles.add(file)
                    }
                } catch (e: Exception) {
                    //ignored. kotlin might not exist
                }
            }

            return alreadyParsedFiles
        }

        private fun getGit(project: Project): Git {
            try {
                val gitDir = getRootGitDir(project.projectDir)
                return Git.wrap(FileRepository(gitDir))
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        private fun getRootGitDir(currentRoot: File): File {
            val gitDir = scanForRootGitDir(currentRoot)
            if (!gitDir.exists()) {
                throw IllegalArgumentException("Cannot find '.git' directory")
            }
            return gitDir
        }

        private fun scanForRootGitDir(currentRoot: File): File {
            val gitDir = File(currentRoot, ".git")

            if (gitDir.exists()) {
                return gitDir
            }

            // always stop at the root directory
            return if (currentRoot.parentFile == null) {
                gitDir
            }
            else scanForRootGitDir(currentRoot.parentFile)
        }
    }

    open class Get : DefaultTask() {
        @TaskAction
        fun run() {
            val version = getVersion(project)

            println("Detected '${project.name}' version is $version")

            // Verifies that all of the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, version, version)

            if (filesWithVersionInfo.isNotEmpty()) {
                println("Detected files with version info are:")

                // list all the files that have detected version information in them
                filesWithVersionInfo.forEach { data ->
                    println("\t${data.file} @ ${data.line}")
                }
            } else {
                throw GradleException("Expecting files with version information, but none were found")
            }
        }
    }

    open class GetDebug : DefaultTask() {
        @TaskAction
        fun run() {
            val version = getVersion(project)

            println("Detected '${project.name}' version is $version")

            // Verifies that all of the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, version, version, true)

            if (filesWithVersionInfo.isNotEmpty()) {
                println("Detected files with version info are:")

                // list all the files that have detected version information in them
                filesWithVersionInfo.forEach { data ->
                    println("\t${data.file} @ ${data.line}")
                }
            } else {
                println("Expecting files with version information, but none were found")
            }
        }
    }
}
