import org.jackhuang.hmcl.gradle.mod.ParseModDataTask
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.shadow)
}

val isOfficial = System.getenv("HMCL_SIGNATURE_KEY") != null
        || (System.getenv("GITHUB_REPOSITORY_OWNER") == "HMCL-dev" && System.getenv("GITHUB_BASE_REF")
    .isNullOrEmpty())

val buildNumber = System.getenv("BUILD_NUMBER")?.toInt().let { number ->
    val offset = System.getenv("BUILD_NUMBER_OFFSET")?.toInt() ?: 0
    if (number != null) {
        (number - offset).toString()
    } else {
        val shortCommit = System.getenv("GITHUB_SHA")?.lowercase()?.substring(0, 7)
        val prefix = if (isOfficial) "dev" else "unofficial"
        if (!shortCommit.isNullOrEmpty()) "$prefix-$shortCommit" else "SNAPSHOT"
    }
}
val versionRoot = System.getenv("VERSION_ROOT") ?: "3.6"
val versionType = System.getenv("VERSION_TYPE") ?: if (isOfficial) "nightly" else "unofficial"

val microsoftAuthId = System.getenv("MICROSOFT_AUTH_ID") ?: ""
val microsoftAuthSecret = System.getenv("MICROSOFT_AUTH_SECRET") ?: ""
val curseForgeApiKey = System.getenv("CURSEFORGE_API_KEY") ?: ""

val launcherExe = System.getenv("HMCL_LAUNCHER_EXE")

version = "$versionRoot.$buildNumber"

dependencies {
    implementation(project(":HMCLCore"))
    implementation("libs:JFoenix")
    implementation(libs.twelvemonkeys.imageio.webp)
    implementation(libs.java.info)

    if (launcherExe == null) {
        implementation("org.glavo.hmcl:HMCLauncher:3.6.0.4")
    }
}

fun digest(algorithm: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(bytes)

fun createChecksum(file: File) {
    val algorithms = linkedMapOf(
        "SHA-1" to "sha1",
        "SHA-256" to "sha256",
        "SHA-512" to "sha512"
    )

    algorithms.forEach { (algorithm, ext) ->
        File(file.parentFile, "${file.name}.$ext").writeText(
            digest(algorithm, file.readBytes()).joinToString(separator = "", postfix = "\n") { "%02x".format(it) }
        )
    }
}

fun attachSignature(jar: File) {
    val keyLocation = System.getenv("HMCL_SIGNATURE_KEY")
    if (keyLocation == null) {
        logger.warn("Missing signature key")
        return
    }

    val privatekey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(keyLocation).readBytes()))
    val signer = Signature.getInstance("SHA512withRSA")
    signer.initSign(privatekey)
    ZipFile(jar).use { zip ->
        zip.stream()
            .sorted(Comparator.comparing { it.name })
            .filter { it.name != "META-INF/hmcl_signature" }
            .forEach {
                signer.update(digest("SHA-512", it.name.toByteArray()))
                signer.update(digest("SHA-512", zip.getInputStream(it).readBytes()))
            }
    }
    val signature = signer.sign()
    FileSystems.newFileSystem(URI.create("jar:" + jar.toURI()), emptyMap<String, Any>()).use { zipfs ->
        Files.newOutputStream(zipfs.getPath("META-INF/hmcl_signature")).use { it.write(signature) }
    }
}

val java11 = sourceSets.create("java11") {
    java {
        srcDir("src/main/java11")
    }
}

tasks.getByName<JavaCompile>(java11.compileJavaTaskName) {
    options.compilerArgs.add("--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED")
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

tasks.jar {
    enabled = false
    dependsOn(tasks["shadowJar"])
}

val jarPath = tasks.jar.get().archiveFile.get().asFile

tasks.shadowJar {
    archiveClassifier.set(null as String?)

    exclude("**/package-info.class")
    exclude("META-INF/maven/**")

    exclude("META-INF/services/javax.imageio.spi.ImageReaderSpi")
    exclude("META-INF/services/javax.imageio.spi.ImageInputStreamSpi")

    listOf(
        "aix-*", "sunos-*", "openbsd-*", "dragonflybsd-*","freebsd-*", "linux-*", "darwin-*",
        "*-ppc", "*-ppc64le", "*-s390x", "*-armel",
    ).forEach { exclude("com/sun/jna/$it/**") }

    minimize {
        exclude(dependency("com.google.code.gson:.*:.*"))
        exclude(dependency("net.java.dev.jna:jna:.*"))
        exclude(dependency("libs:JFoenix:.*"))
    }

    manifest {
        attributes(
            "Created-By" to "Copyright(c) 2013-2025 huangyuhui.",
            "Main-Class" to "org.jackhuang.hmcl.Main",
            "Multi-Release" to "true",
            "Implementation-Version" to project.version,
            "Microsoft-Auth-Id" to microsoftAuthId,
            "Microsoft-Auth-Secret" to microsoftAuthSecret,
            "CurseForge-Api-Key" to curseForgeApiKey,
            "Build-Channel" to versionType,
            "Class-Path" to "pack200.jar",
            "Add-Opens" to listOf(
                "java.base/java.lang",
                "java.base/java.lang.reflect",
                "java.base/jdk.internal.loader",
                "javafx.base/com.sun.javafx.binding",
                "javafx.base/com.sun.javafx.event",
                "javafx.base/com.sun.javafx.runtime",
                "javafx.graphics/javafx.css",
                "javafx.graphics/com.sun.javafx.stage",
                "javafx.graphics/com.sun.prism",
                "javafx.controls/com.sun.javafx.scene.control",
                "javafx.controls/com.sun.javafx.scene.control.behavior",
                "javafx.controls/javafx.scene.control.skin",
                "jdk.attach/sun.tools.attach",
            ).joinToString(" "),
            "Enable-Native-Access" to "ALL-UNNAMED"
        )

        System.getenv("GITHUB_SHA")?.also {
            attributes("GitHub-SHA" to it)
        }
    }

    if (launcherExe != null) {
        into("assets") {
            from(file(launcherExe))
        }
    }

    doLast {
        attachSignature(jarPath)
        createChecksum(jarPath)
    }
}

tasks.processResources {
    into("META-INF/versions/11") {
        from(sourceSets["java11"].output)
    }
    dependsOn(tasks["java11Classes"])
}

val makeExecutables by tasks.registering {
    val extensions = listOf("exe", "sh")

    dependsOn(tasks.jar)

    inputs.file(jarPath)
    outputs.files(extensions.map { File(jarPath.parentFile, jarPath.nameWithoutExtension + '.' + it) })

    doLast {
        val jarContent = jarPath.readBytes()

        ZipFile(jarPath).use { zipFile ->
            for (extension in extensions) {
                val output = File(jarPath.parentFile, jarPath.nameWithoutExtension + '.' + extension)
                val entry = zipFile.getEntry("assets/HMCLauncher.$extension")
                    ?: throw GradleException("HMCLauncher.$extension not found")

                output.outputStream().use { outputStream ->
                    zipFile.getInputStream(entry).use { it.copyTo(outputStream) }
                    outputStream.write(jarContent)
                }

                createChecksum(output)
            }
        }
    }
}

val downloadJRE by tasks.registering {
    description = "Download JRE for AppImage packaging"
    group = "distribution"
    
    val jreDir = File(layout.buildDirectory.get().asFile, "jre")
    
    outputs.dir(jreDir)
    
    doLast {
        // Check if JRE directory already exists
        if (jreDir.exists()) {
            logger.lifecycle("JRE already exists at: ${jreDir.absolutePath}")
            return@doLast
        }
        
        // Determine platform and architecture
        val osName = System.getProperty("os.name").lowercase()
        
        val platform = when {
            osName.contains("linux") -> "linux"
            osName.contains("mac") -> "mac"
            osName.contains("win") -> "windows"
            else -> "linux" // default to linux
        }
        
        // For AppImage, we focus on Linux platform
        if (platform == "linux") {
            // Try to use system JRE first
            val javaHome = System.getProperty("java.home")
            val systemJreDir = File(javaHome)
            
            if (systemJreDir.exists()) {
                logger.lifecycle("Using system JRE from: ${systemJreDir.absolutePath}")
                systemJreDir.copyRecursively(jreDir)
            } else {
                logger.warn("System JRE not found. AppImage will use system Java at runtime.")
                // Create empty directory to satisfy task outputs
                jreDir.mkdirs()
            }
        } else {
            logger.warn("JRE download not supported for platform: $platform")
            jreDir.mkdirs()
        }
    }
}

val makeAppImage by tasks.registering {
    description = "Create AppImage package with jar and jre"
    group = "distribution"
    
    dependsOn(tasks.jar, downloadJRE)
    
    inputs.file(jarPath)
    outputs.file(File(jarPath.parentFile, jarPath.nameWithoutExtension + ".AppImage"))
    
    doLast {
        val appImageDir = File(jarPath.parentFile, "AppImage")
        val appDir = File(appImageDir, "HMCL.AppDir")
        
        // Clean up any existing AppImage directory
        if (appImageDir.exists()) {
            appImageDir.deleteRecursively()
        }
        
        // Create AppImage directory structure
        appDir.mkdirs()
        File(appDir, "usr/bin").mkdirs()
        File(appDir, "usr/share/applications").mkdirs()
        File(appDir, "usr/share/pixmaps").mkdirs()
        File(appDir, "usr/share/java").mkdirs()
        
        // Copy JAR file
        jarPath.copyTo(File(appDir, "usr/share/java/HMCL.jar"))
        
        // Copy JRE if available
        val jreDir = File(layout.buildDirectory.get().asFile, "jre")
        if (jreDir.exists() && jreDir.listFiles()?.isNotEmpty() == true) {
            val jvmDir = File(appDir, "usr/lib/jvm")
            jvmDir.mkdirs()
            jreDir.copyRecursively(jvmDir)
            logger.lifecycle("JRE included in AppImage")
        } else {
            logger.warn("JRE not found, AppImage will use system Java")
        }
        
        // Create AppRun script
        val appRunScript = File(appDir, "AppRun")
        appRunScript.writeText("""#!/bin/bash
HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}{0}")")"
export PATH="${'$'}HERE/usr/bin:${'$'}PATH"
export LD_LIBRARY_PATH="${'$'}HERE/usr/lib:${'$'}LD_LIBRARY_PATH"
export XDG_DATA_DIRS="${'$'}HERE/usr/share:${'$'}XDG_DATA_DIRS"

# Try to use bundled JRE first, then fall back to system Java
if [ -d "${'$'}HERE/usr/lib/jvm" ]; then
    export JAVA_HOME="${'$'}HERE/usr/lib/jvm"
    export PATH="${'$'}JAVA_HOME/bin:${'$'}PATH"
    JAVA_CMD="${'$'}JAVA_HOME/bin/java"
else
    JAVA_CMD="java"
fi

exec "${'$'}JAVA_CMD" -jar "${'$'}HERE/usr/share/java/HMCL.jar" "${'$'}@"
""")
        appRunScript.setExecutable(true)
        
        // Create .desktop file
        val desktopFile = File(appDir, "HMCL.desktop")
        desktopFile.writeText("""[Desktop Entry]
Type=Application
Name=HMCL
Comment=Hello Minecraft! Launcher
Exec=HMCL
Icon=HMCL
Categories=Game;
StartupNotify=true
""")
        
        // Copy icon from resources
        val resourcesDir = File(project.projectDir, "src/main/resources/assets/img")
        val iconFile = File(resourcesDir, "icon.png")
        if (iconFile.exists()) {
            iconFile.copyTo(File(appDir, "HMCL.png"))
            iconFile.copyTo(File(appDir, "usr/share/pixmaps/HMCL.png"))
        }
        
        // Check if appimagetool is available
        val appimagetoolResult = try {
            val result = ProcessBuilder("which", "appimagetool")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            result.waitFor()
            result.exitValue()
        } catch (e: Exception) {
            1 // not found
        }
        
        if (appimagetoolResult == 0) {
            // Use appimagetool to create AppImage
            val appImageFile = File(jarPath.parentFile, jarPath.nameWithoutExtension + ".AppImage")
            val appimagetoolProcess = ProcessBuilder("appimagetool", appDir.absolutePath, appImageFile.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val exitCode = appimagetoolProcess.waitFor()
            
            if (exitCode == 0) {
                createChecksum(appImageFile)
                logger.lifecycle("AppImage created: ${appImageFile.absolutePath}")
            } else {
                logger.error("appimagetool failed with exit code: $exitCode")
            }
        } else {
            logger.warn("appimagetool not found. AppImage directory created at: ${appDir.absolutePath}")
            logger.warn("To create AppImage, install appimagetool and run: appimagetool ${appDir.absolutePath}")
        }
    }
}

tasks.build {
    dependsOn(makeExecutables)
}

fun parseToolOptions(options: String?): MutableList<String> {
    if (options == null)
        return mutableListOf()

    val builder = StringBuilder()
    val result = mutableListOf<String>()

    var offset = 0

    loop@ while (offset < options.length) {
        val ch = options[offset]
        if (Character.isWhitespace(ch)) {
            if (builder.isNotEmpty()) {
                result += builder.toString()
                builder.clear()
            }

            while (offset < options.length && Character.isWhitespace(options[offset])) {
                offset++
            }

            continue@loop
        }

        if (ch == '\'' || ch == '"') {
            offset++

            while (offset < options.length) {
                val ch2 = options[offset++]
                if (ch2 != ch) {
                    builder.append(ch2)
                } else {
                    continue@loop
                }
            }

            throw GradleException("Unmatched quote in $options")
        }

        builder.append(ch)
        offset++
    }

    if (builder.isNotEmpty()) {
        result += builder.toString()
    }

    return result
}

tasks.register<JavaExec>("run") {
    dependsOn(tasks.jar)

    group = "application"

    classpath = files(jarPath)
    workingDir = rootProject.rootDir

    val vmOptions = parseToolOptions(System.getenv("HMCL_JAVA_OPTS"))
    if (vmOptions.none { it.startsWith("-Dhmcl.offline.auth.restricted=") })
        vmOptions += "-Dhmcl.offline.auth.restricted=false"

    jvmArgs(vmOptions)

    val hmclJavaHome = System.getenv("HMCL_JAVA_HOME")
    if (hmclJavaHome != null) {
        this.executable(
            file(hmclJavaHome).resolve("bin")
                .resolve(if (System.getProperty("os.name").lowercase().startsWith("windows")) "java.exe" else "java")
        )
    }

    doFirst {
        logger.quiet("HMCL_JAVA_OPTS: {}", vmOptions)
        logger.quiet("HMCL_JAVA_HOME: {}", hmclJavaHome ?: System.getProperty("java.home"))
    }
}

// mcmod data

tasks.register<ParseModDataTask>("parseModData") {
    inputFile.set(layout.projectDirectory.file("mod.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/mod_data.txt"))
}

tasks.register<ParseModDataTask>("parseModPackData") {
    inputFile.set(layout.projectDirectory.file("modpack.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/modpack_data.txt"))
}
