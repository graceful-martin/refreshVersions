package de.fayard.refreshVersions.core.internal

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.storage.Bucket
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import de.fayard.refreshVersions.core.DependencyVersionsFetcher
import de.fayard.refreshVersions.core.ModuleId
import de.fayard.refreshVersions.core.Version
import de.fayard.refreshVersions.core.extensions.okhttp.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.GradleException
import org.gradle.caching.BuildCacheException
import retrofit2.HttpException
import retrofit2.Response
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

internal class MavenDependencyVersionsFetcherGCS(
    moduleId: ModuleId,
    repoUrl: String
) : MavenDependencyVersionsFetcher(
    moduleId = moduleId,
    repoUrl = repoUrl
) {
    override suspend fun getMetadata(): String {
        return getBlob("${moduleId.group!!.replace('.', '/')}/${moduleId.name}/maven-metadata.xml")
    }

    private val bucketName = repoUrl.substringAfter("gcs://").substringBefore("/")
    private val repoPath = repoUrl.substringAfter("gcs://").substringAfter("/")

    private val bucket: Bucket

    init {
        try {

            val storage = StorageOptions.newBuilder()
                .setCredentials(ServiceAccountCredentials.fromStream(FileInputStream(CREDENTIALS_PATH)))
                .build()
                .service

            bucket = storage.get(bucketName) ?: throw BuildCacheException("$bucketName is unavailable")

        } catch (e: FileNotFoundException) {
            throw GradleException("Unable to load credentials from $CREDENTIALS_PATH.", e)
        } catch (e: IOException) {
            throw GradleException("Unable to access Google Cloud Storage bucket '$bucketName'.", e)
        } catch (e: StorageException) {
            throw GradleException("Unable to access Google Cloud Storage bucket '$bucketName'.", e)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun getBlob(path: String): String {
        val fullPath = listOfNotNull(repoPath, path).joinToString("/", "", "")
        try {
            val blob = bucket.get(fullPath) ?: throw FileNotFoundException("blob $fullPath is missing")
            val stringContent = blob.getContent().decodeToString()

            System.err.println("returning $stringContent")
            return stringContent

        } catch (e: StorageException) {
            // https://github.com/googleapis/google-cloud-java/issues/3402
            if (e.message?.contains("404") == true) {
                throw FileNotFoundException("missing blob")
            }

            throw FileNotFoundException("Unable to load '$fullPath' from Google Cloud Storage bucket '$bucketName'.")
        }

    }

    companion object {
        private val CREDENTIALS_PATH = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
            ?: error("required environment variable 'GOOGLE_APPLICATION_CREDENTIALS' missing")
    }
}
