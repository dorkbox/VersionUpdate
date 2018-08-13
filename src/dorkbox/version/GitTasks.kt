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

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException



class
GitTasks {
    companion object {
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

    open class CreateTag : DefaultTask() {
        @TaskAction
        fun run() {
            // make sure there are no uncommitted files, only exception is build files + files with version info
            // because it is natural to expect that creating a git tag would happen IMMEDIATELY after incrementing the version

            val version = VersionPlugin.getVersion(project)


            // make sure all code is committed (no un-committed files and no staged files). Throw error and exit if there is
            val git = getGit(project)
            val status = git.status().call()

            if (status.hasUncommittedChanges()) {
                // have to get the git "root" project location (for which all of the uncommitted files are based on)
                val subStringLength = git.repository.directory.parentFile.absolutePath.length + 1

                // combo of added/changed/removed/missing/modified/conflicting.
                val uncommittedFiles = status.uncommittedChanges

                // have to change the full path, to it's relative path to the .git root dir
                val filesWithVersionInfo = VersionPlugin.verifyVersion(project, version).map { data -> data.file.absolutePath.substring(subStringLength) }

                uncommittedFiles.removeAll(filesWithVersionInfo)

                if (uncommittedFiles.isNotEmpty()) {
                    println("Please commit or stash the following files: $uncommittedFiles")
                    throw GradleException("Cannot create and save a GIT tag when files have not been committed")
                }
            }


            // the ony files now allowed to not be committed are our build + other version info files.
            val tagName = "Version_$version"

            // do we already have this tag?
            val call = git.tagList().call()
            for (ref in call) {
                if (ref.name == tagName) {
                    throw GradleException("Tag $tagName already exists. Please delete the old tag in order to continue.")
                }
            }

            // create a git tag on the current HEAD
            val tag = git.tag().setName(tagName).call()
            println("Successfully created tag $tagName")
        }
    }
}
