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

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class
IncrementTasks {
    open class Major : DefaultTask() {
        @TaskAction
        fun run() {
            val version = VersionPlugin.getVersion(project)

            // make an update to the version number based on major/minor/patch
            val incrementMajorVersion = version.incrementMajorVersion()

            println("Incrementing Major version from $version -> $incrementMajorVersion")

            // update the all java files and build file with the new version number
            VersionPlugin.saveVersionInFiles(project, version, incrementMajorVersion)
        }
    }

    open class Minor : DefaultTask() {
        @TaskAction
        fun run() {
            val version = VersionPlugin.getVersion(project)

            // make an update to the version number based on major/minor/patch
            val incrementMinorVersion = version.incrementMinorVersion()

            println("Incrementing Minor version from $version -> $incrementMinorVersion")

            // update the all java files and build file with the new version number
            VersionPlugin.saveVersionInFiles(project, version, incrementMinorVersion)
        }
    }

    open class Patch : DefaultTask() {
        @TaskAction
        fun run() {
            val version = VersionPlugin.getVersion(project)

            // make an update to the version number based on major/minor/patch
            val incrementPatchVersion = version.incrementPatchVersion()

            println("Incrementing Patch version from $version -> $incrementPatchVersion")

            // update the all java files and build file with the new version number
            VersionPlugin.saveVersionInFiles(project, version, incrementPatchVersion)
        }
    }
}
