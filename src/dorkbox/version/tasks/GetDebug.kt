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

package dorkbox.version.tasks

import dorkbox.version.VersionPlugin.Companion.verifyVersion
import org.gradle.api.tasks.TaskAction

open class GetDebug : ProjectSavedTask() {
    @TaskAction
    fun run() {
        val project = savedProject.get()

        println("\tDetected '${project.name}' version is $version")

        // Verifies that all of the project files are set to the specified version
        val filesWithVersionInfo = verifyVersion(project, version, version, true)

        if (filesWithVersionInfo.isNotEmpty()) {
            println("\tDetected files with version info:")

            // list all the files that have detected version information in them
            filesWithVersionInfo.forEach { data ->
                println("\t ${data.file} @ ${data.line}")
            }
        } else {
            println("\tExpecting files with version information, but none were found")
        }
    }
}
