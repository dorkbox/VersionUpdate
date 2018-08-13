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
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import java.io.File
import java.io.IOException


/**
 * For automatically setting version information based on a MAJOR, MINOR, or PATCH update based on a build definition file
 */
class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {

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
        private val buildText = """version""".toRegex()
        private val javaText = """String getVersion\(\) \{""".toRegex()
        private val kotlinText = """getVersion\(\) : String \{""".toRegex()

        private val versionText = """(return ")(.*)(")""".toRegex()
        // version = '1.0.0'
        // project.version = '1.0.0'
        private val buildFileVersionText = """(.*version\s*=\s*'|"\s*)(.*)('|")""".toRegex()

        data class VerData(val file: File, val line: Int, val version: String, val isBuildFile: Boolean)

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


        fun saveVersionInFilesAndCreateTag(project: Project, oldVersion: Version, newVersion: Version) {
            // make sure all code is committed (no un-committed files and no staged files). Throw error and exit if there is
            val git = getGit(project)
            val status = git.status().call()

            if (status.hasUncommittedChanges()) {
                println("Please commit or stash the following files: ${status.uncommittedChanges}")
                throw GradleException("Cannot continue when files have not been committed")
            }



            // Verifies that all of the project files are set to the specified version
            val filesWithVersionInfo = verifyVersion(project, oldVersion)

            // now save the NEW version to all of the files (this also has our build file)
            filesWithVersionInfo.forEach { data ->
                run {
                    var lineNumber = 1  // visual editors start at 1, so we should too
                    val tempFile = createTempFile()

                    tempFile.printWriter().use { writer ->
                        when {
                            data.file.extension == "java" -> {
                                data.file.useLines { lines ->
                                    lines.forEach { line ->
                                        if (lineNumber == data.line) {
                                            writer.println(line.replace(oldVersion.toString(), newVersion.toString()))
                                            println("Updating file '${data.file}' to version $newVersion at line $lineNumber")
                                        }
                                        else {
                                            writer.println(line)
                                        }
                                        lineNumber++
                                    }
                                }
                            }
                            data.file.extension == "kt" -> {
                                data.file.useLines { lines ->
                                    lines.forEach { line ->
                                        if (lineNumber == data.line) {
                                            writer.println(line.replace(oldVersion.toString(), newVersion.toString()))
                                            println("Updating file '${data.file}' to version $newVersion at line $lineNumber")
                                        }
                                        else {
                                            writer.println(line)
                                        }
                                        lineNumber++
                                    }
                                }
                            }
                            data.isBuildFile -> {
                                data.file.useLines { lines ->
                                    lines.forEach { line ->
                                        if (lineNumber == data.line) {
                                            writer.println(line.replace(oldVersion.toString(), newVersion.toString()))
                                            println("Updating file '${data.file}' to version $newVersion at line $lineNumber")
                                        }
                                        else {
                                            writer.println(line)
                                        }
                                        lineNumber++
                                    }
                                }
                            }
                            else -> {
                                // shouldn't get here, but just in case...
                                throw GradleException("Cannot process unknown file type ${data.file}. Aborting")
                            }
                        }
                    }

                    check(data.file.delete() && tempFile.renameTo(data.file)) { "Failed to replace file" }
                }
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


            // now make sure the files were actually updated
            val updatedFilesWithVersionInfo = VersionPlugin.verifyVersion(project, newVersion)

            updatedFilesWithVersionInfo.forEach {
                // now add the file to git
                git.add().addFilepattern(it.file.absolutePath).call()
            }

            // now commit these updated files
            git.commit().setMessage("Updated version from $oldVersion to $newVersion").call()


            // now create the git tag
            git.tag().setName(tagName).call()

            println("Successfully updated version information and created git tag $tagName" )
        }

        /**
         * Verifies that all of the project files are set to the specified version
         */
        internal fun verifyVersion(project: Project, version: Version): List<VerData> {
            val alreadyParsedFiles = getSourceFiles(project)
            val filesWithVersionInfo = HashMap<File, VerData>()


            // collect all of the class files that have a version defined (look in source sets. must match our pre-defined pattern)
            alreadyParsedFiles.forEach { file ->
                run fileCheck@{
                    var matchesText = false
                    var lineNumber = 1  // visual editors start at 1, so we should too

                    if (file.extension == "java") {
                        file.useLines { lines ->
                            lines.forEach { line ->
                                if (line.contains(javaText)) {
                                    matchesText = true
                                }
                                else if (matchesText) {
                                    val matchResult = versionText.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        filesWithVersionInfo[file] = VerData(file, lineNumber, ver, false)
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
                                    matchesText = true
                                }
                                else if (matchesText) {
                                    val matchResult = versionText.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        filesWithVersionInfo[file] = VerData(file, lineNumber, ver, false)
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
                            if (ver == version.toString()) {
                                filesWithVersionInfo[project.buildFile] = VerData(project.buildFile, lineNumber, ver, true)
                                return@useLines
                            }
                        }
                    }
                    lineNumber++
                }
            }

            // make sure version info all match (throw error and exit if they do not)
            filesWithVersionInfo.forEach { f, v ->
                // println("Verifying file '$f' for version '${v.version} at line ${v.line}'")
                if (Version.from(v.version) != version) {
                    throw GradleException("Version information mismatch, expected $version, got ${v.version} in file: $f at line ${v.line}")
                }
            }

            return filesWithVersionInfo.values.toList()
        }

        private fun getSourceFiles(project: Project): HashSet<File> {
            val alreadyParsedFiles = HashSet<File>()

            val convention = project.convention.getPlugin(JavaPluginConvention::class.java)
            convention.sourceSets.all {sourceSet ->
                sourceSet.java {directorySet ->
                    directorySet.forEach { file ->
                        alreadyParsedFiles.add(file)
                    }
                }
            }

            return alreadyParsedFiles
        }

        internal fun getGit(project: Project): Git {
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
}
