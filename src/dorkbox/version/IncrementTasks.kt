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
