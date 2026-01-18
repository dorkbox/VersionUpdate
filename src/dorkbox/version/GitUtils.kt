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

package dorkbox.version

import dorkbox.version.VersionPlugin.Companion.verifyVersion
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.io.IOException

object GitUtils {

    /**
     * Gets the most recent commit hash in the specified repository.
     */
    fun gitCommitHash(directory: File, length: Int = 7) : String {
        val latestCommit = getGit(directory).log().setMaxCount(1).call().iterator().next()
        val latestCommitHash = latestCommit.name

        return if (latestCommitHash?.isNotEmpty() == true) {
            val maxLength = length.coerceAtMost(latestCommitHash.length)
            latestCommitHash.substring(0, maxLength)
        } else {
            "NO_HASH"
        }
    }

    fun Project.getGit(): Git {
        try {
            val gitDir = getRootGitDir(projectDir)
            return Git.wrap(FileRepository(gitDir))
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun Project.createTag(newVersion: Version) {
        // make sure all code is committed (no un-committed files and no staged files). Throw error and exit if there is
        val git = getGit()
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

        // Verifies that all the project files are set to the specified version
        val filesWithVersionInfo = verifyVersion(this, newVersion, newVersion)
        val files = filesWithVersionInfo.map { verData -> verData.file }

        // must include the separator.
        val projectPath = buildFile.parentFile.normalize().absolutePath + File.separator

        files.forEach {
            // now add the file to git. MUST BE repository-relative path!
            val filePath = it.normalize().absolutePath.replace(projectPath, "")
            git.add().addFilepattern(filePath).call()
        }

        // now create the git tag
        git.tag().setName(tagName).call()

        println("Successfully created git tag $tagName" )
    }

    private fun getGit(directory: File): Git {
        try {
            val gitDir = getRootGitDir(directory)
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
