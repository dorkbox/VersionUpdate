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


/**
 * For automatically setting version information based on a MAJOR, MINOR, or PATCH update based on a build definition file
 */
class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {

        project.tasks.create("get", Get::class.java).apply {
            group = "version"
        }
        project.tasks.create("tag", IncrementTasks.Tag::class.java).apply {
            group = "version"
        }
        project.tasks.create("incrementMajor", IncrementTasks.Major::class.java).apply {
            group = "version"
        }
        project.tasks.create("incrementMinor", IncrementTasks.Minor::class.java).apply {
            group = "version"
        }
        project.tasks.create("incrementPatch", IncrementTasks.Patch::class.java).apply {
            group = "version"
        }

        project.afterEvaluate {
            // just make sure that we have a version defined.
            val version = project.version.toString()
            project.version.toString()

            if (version.isBlank() || version == Project.DEFAULT_VERSION) {
                // no version info specified, but version task was called
                throw GradleException("Project version information is unset. Please set via `project.version = '1.0.0'`")
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
        private val kotlinText2 = """const val version\s*=""".toRegex()
        private val kotlinText2VersionText = """(version\s*=\s*")(.*)(")""".toRegex()

        // return "...."
        private val versionText = """(return ")(.*)(")""".toRegex()


        // version = '1.0.0'
        // project.version = '1.0.0'
        // version = "1.0.0"
        // project.version = "1.0.0"
        private val buildFileVersionText = """(.*version\s*=\s*'|"\s*)(.*)('|"')""".toRegex()



        /*
            Maven Info
            ---------
            ````
            <dependencies>
                ...
                <dependency>
                    <groupId>com.dorkbox</groupId>
                    <artifactId>SystemTray</artifactId>
                    <version>3.14</version>
                </dependency>
            </dependencies>
            ````


            Gradle Info
            ---------
            ````
            dependencies {
                ...
                compile 'com.dorkbox:SystemTray:3.14'
            }
            ````
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


        fun saveNewVersionInfo(project: Project, oldVersion: Version, newVersion: Version) {
            // Verifies that all of the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, oldVersion, newVersion)

            // now save the NEW version to all of the files (this also has our build + README files)
            filesWithVersionInfo.forEach { data ->
                var lineNumber = 1  // visual editors start at 1, so we should too
                val tempFile = createTempFile()

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
        private fun verifyVersion(project: Project, oldVersion: Version, newVersion: Version): List<VerData> {
            val alreadyParsedFiles = getSourceFiles(project)
            val filesWithVersionInfo = ArrayList<VerData>()


            // collect all of the class files that have a version defined (look in source sets. must match our pre-defined pattern)
            alreadyParsedFiles.forEach { file ->
                run fileCheck@{
                    var matchesText = false
                    var lineNumber = 1  // visual editors start at 1, so we should too

                    if (file.extension == "java") {
                        file.useLines { lines ->
                            lines.forEach { line ->
                                if (line.contains(javaText)) {
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
                                    // this is so the text is matched on the following line
                                    matchesText = true
                                } else if (line.contains(kotlinText2)) {
                                    // same line
                                    val matchResult = kotlinText2VersionText.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        if (ver == oldVersion.toString()) {
                                            val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                            filesWithVersionInfo.add(VerData(file, lineNumber, ver, lineReplacement))
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

            // get version info by file parsing from gradle.build file
            project.buildFile.useLines { lines ->
                var lineNumber = 1  // visual editors start at 1, so we should too

                lines.forEach { line ->
                    if (line.contains(buildText)) {
                        val matchResult = buildFileVersionText.find(line)
                        if (matchResult != null) {
                            val (_, ver, _) = matchResult.destructured
                            // verify it's what we think it is
                            if (ver == oldVersion.toString()) {
                                val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                filesWithVersionInfo.add(VerData(project.buildFile, lineNumber, ver, lineReplacement))
                                return@useLines
                            }
                        }
                    }
                    lineNumber++
                }
            }

            // get version info by parsing the README.MD file, if it exists (OPTIONAL)
            // this file will always exist next to the build file. We should ignore case (because yay windows!)
            var readmeFile: File? = null
            project.buildFile.parentFile.listFiles()?.forEach {
                if (it.name.toLowerCase() == "readme.md") {
                    readmeFile = it
                    return@forEach
                }
            }

            // it won't always exist, but if it does, process it as well
            // TWO version entries possible. One for MAVEN and one for GRADLE
            if (readmeFile != null && readmeFile!!.canRead()) {
                val readme = readmeFile!!

                readme.useLines { lines ->
                    var lineNumber = 1  // visual editors start at 1, so we should too

                    var foundMaven = false
                    var foundGradle = false

                    var readyMaven = false
                    var readyGradle = false

                    // file has MAVEN info first, followed by GRADLE info
                    lines.forEach { line ->
                        when {
                            !foundMaven && line.trim() == readmeMavenInfoText -> foundMaven = true
                            !foundGradle && line.trim() == readmeGradleInfoText -> foundGradle = true
                            foundMaven && line.trim() == readmeTicksText -> readyMaven = true
                            foundGradle && line.trim() == readmeTicksText -> readyGradle = true
                            readyGradle && line.trim() == readmeTicksText -> foundMaven = false
                            readyGradle && line.trim() == readmeTicksText -> foundGradle = false

                            // block that maven stuff is in
                            foundMaven && readyMaven -> {
                                val matchResult = readmeMavenText.find(line)
                                if (matchResult != null) {
                                    val (_, ver, _) = matchResult.destructured
                                    // verify it's what we think it is
                                    if (ver == oldVersion.toString()) {
                                        val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                        filesWithVersionInfo.add(VerData(readme, lineNumber, ver, lineReplacement))
                                        foundMaven = false
                                        readyMaven = false
                                        // println("FOUND MAVEN INFO $ver")
                                        // return@useLines // keep going, since we have to look for gradle info too
                                    }
                                }
                            }

                            // block that gradle stuff is in
                            foundGradle && readyGradle -> {
                                val matchResult = readmeGradleText.find(line)
                                if (matchResult != null) {
                                    val (_, ver, _) = matchResult.destructured
                                    // verify it's what we think it is
                                    if (ver == oldVersion.toString()) {
                                        val lineReplacement = line.replace(oldVersion.toString(), newVersion.toString())
                                        filesWithVersionInfo.add(VerData(readme, lineNumber, ver, lineReplacement))
                                        foundGradle = false
                                        readyGradle = false
                                        // println("FOUND GRADLE INFO $ver")
                                        return@useLines
                                    }
                                }
                            }
                        }

                        lineNumber++
                    }
                }
            }


            // make sure version info all match (throw error and exit if they do not)
            filesWithVersionInfo.forEach { v ->
                // println("Verifying file '$f' for version '${v.version} at line ${v.line}'")
                if (Version.from(v.version) != oldVersion) {
                    throw GradleException("Version information mismatch, expected $oldVersion, got ${v.version} in file: ${v.file} at line ${v.line}")
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
        init {
            description = "Outputs the detected version to the console"
        }

        @TaskAction
        fun run() {
            val version = getVersion(project)

            println("Detected version is $version")

            // Verifies that all of the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, version, version)

            if (filesWithVersionInfo.isNotEmpty()) {
                println("\tDetected files with version are:")

                // list all the files that have detected version information in them
                filesWithVersionInfo.forEach { data ->
                    println("\t${data.file} @ ${data.line}")
                }
            } else {
                throw GradleException("Expecting files with version information, but none were found")
            }
        }
    }
}
