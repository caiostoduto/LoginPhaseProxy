import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

abstract class DownloadModrinthVersion : DefaultTask() {

    /**
     * Modrinth project ID or slug.
     * Example: "fabric-api"
     */
    @get:Input
    abstract val projectId: Property<String>

    /**
     * Modrinth version ID.
     * Example: "AANobbMI"
     */
    @get:Input
    abstract val versionId: Property<String>

    /**
     * Optional filename matcher.
     * Useful when a version contains multiple files.
     */
    @get:Optional
    @get:Input
    abstract val fileName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val project = projectId.get()
        val version = versionId.get()
        val desiredFileName = fileName.orNull

        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        val metadataUrl = "https://api.modrinth.com/v2/project/$project/version/$version"
        val metadata = request(client, URI.create(metadataUrl))

        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parseText(metadata) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val files = json["files"] as List<Map<String, Any?>>

        val selectedFile = when {
            desiredFileName != null -> {
                files.firstOrNull { it["filename"] == desiredFileName }
                    ?: error("Could not find file '$desiredFileName' in Modrinth version $version")
            }

            files.isNotEmpty() -> files.first()

            else -> error("No files found for Modrinth version $version")
        }

        val downloadUrl = selectedFile["url"] as String
        @Suppress("UNCHECKED_CAST")
        val expectedSha1 = selectedFile["hashes"].let { it as Map<String, Any?> }["sha1"] as String

        val target = outputFile.get().asFile

        if (target.isFile && target.sha1().equals(expectedSha1, ignoreCase = true)) {
            logger.lifecycle(
                "Using cached Modrinth artifact for $project version $version at ${target.absolutePath}"
            )
            return
        }

        target.parentFile.mkdirs()

        val temp = temporaryDir.resolve(selectedFile["filename"] as String)

        val bytes = requestBytes(client, URI.create(downloadUrl))
        temp.writeBytes(bytes)

        val actualSha1 = temp.sha1()

        check(actualSha1.equals(expectedSha1, ignoreCase = true)) {
            "Downloaded Modrinth file SHA-1 mismatch. Expected $expectedSha1, got $actualSha1."
        }

        temp.copyTo(target, overwrite = true)

        logger.lifecycle(
            "Downloaded Modrinth artifact for $project version $version to ${target.absolutePath}"
        )
    }

    private fun request(client: HttpClient, uri: URI): String =
        client.send(
            HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", "Gradle build")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).let { response ->
            check(response.statusCode() in 200..299) {
                "GET $uri failed with HTTP ${response.statusCode()}: ${response.body()}"
            }
            response.body()
        }

    private fun requestBytes(client: HttpClient, uri: URI): ByteArray =
        client.send(
            HttpRequest.newBuilder(uri)
                .header("User-Agent", "Gradle build")
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        ).let { response ->
            check(response.statusCode() in 200..299) {
                "GET $uri failed with HTTP ${response.statusCode()}"
            }
            response.body()
        }

    private fun File.sha1(): String {
        val digest = MessageDigest.getInstance("SHA-1")

        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }

                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}