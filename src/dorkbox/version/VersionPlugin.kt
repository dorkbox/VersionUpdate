package dorkbox.version

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import java.io.File
import java.io.IOException



/**
 * For automatically updating version information based on a MAJOR, MINOR, or PATCH update based on a build definition file
 */
class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.afterEvaluate {
            // get the version info from project.version
            val version = project.version

            val javaText = """String getVersion\(\) \{""".toRegex()
            val kotlinText = """getVersion\(\) : String \{""".toRegex()

            val versionText = """(return ")(.*)(")""".toRegex()

//            val readmeOrigText = "return \"" + original.toString() + "\";"
//            val readmeNewText = "return \"" + toString() + "\";"

            val alreadyParsedFiles = getSourceFiles(project)
            val filesWithVersionInfo = HashMap<File, String>()


            // collect all of the class files that have a version defined (look in source sets. must match our pre-defined pattern)
            alreadyParsedFiles.forEach { file ->
                run fileCheck@{
                    var matchesText = false;
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
                                        filesWithVersionInfo[file] = ver
                                        return@fileCheck
                                    }
                                }
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
                                        filesWithVersionInfo[file] = ver
                                        return@fileCheck
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // make sure version info all match (throw error and exit if they do not)
            filesWithVersionInfo.forEach { f, v ->
                if (v != version) {
                    throw GradleException("Version information mismatch, expected $version, got $v in file: $f")
                }
            }

            // make sure all code is committed (no un-committed files and no staged files). Throw error and exit if there is

            // make an update to the version number based on major/minor/patch

            // update the all java files and build file with the new version number

            // make a git tag with the NEW version number, commit the tag


            // Version will increment/update only if the project needed to build. This is current, not the new one that will be used to build
    //        public static BuildVersion version = BuildVersion . get ("3.6").readme(readme).sourceFile(srcName, src, dorkbox.console.Console.class);

        }
    }

    private fun getSourceFiles(project: Project): HashSet<File> {
        val alreadyParsedFiles = HashSet<File>()

        val convention = project.convention.getPlugin(JavaPluginConvention::class.java)
        convention.sourceSets.all {
            it.java {
                it.forEach { file ->
                    alreadyParsedFiles.add(file)
                }
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

        // stop at the root directory, return non-existing File object;
        return if (currentRoot.parentFile == null) {
            gitDir
        }
        else scanForRootGitDir(currentRoot.parentFile)

        // look in parent directory;
    }

}
