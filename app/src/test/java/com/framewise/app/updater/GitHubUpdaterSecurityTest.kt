package com.framewise.app.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitHubUpdaterSecurityTest {

    private val owner = "omarpromax2021do-droid"
    private val repo = "FrameBoost"

    @Test
    fun allowedApiOrRepoUrls_strictlyAllowOnlyHttpsForConfiguredRepo() {
        assertTrue(
            UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(
                "https://api.github.com/repos/$owner/$repo/releases/latest",
                owner,
                repo
            )
        )
        assertTrue(
            UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(
                "https://github.com/$owner/$repo/releases/download/v1.0.1/Framewise-v1.0.1.apk",
                owner,
                repo
            )
        )

        // Reject insecure HTTP
        assertFalse(
            UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(
                "http://api.github.com/repos/$owner/$repo/releases/latest",
                owner,
                repo
            )
        )
        // Reject other owner or repo
        assertFalse(
            UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(
                "https://api.github.com/repos/otheruser/$repo/releases/latest",
                owner,
                repo
            )
        )
        assertFalse(
            UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(
                "https://github.com/$owner/OtherRepo/releases/download/v1.0.1/app.apk",
                owner,
                repo
            )
        )
        // Reject external hosts
        assertFalse(
            UpdaterSecurityPolicy.isAllowedApiOrRepoUrl(
                "https://evil.example.com/$owner/$repo/app.apk",
                owner,
                repo
            )
        )
    }

    @Test
    fun redirectTargetPolicy_allowsGitHubAssetCdnOverHttpsOnly() {
        assertTrue(
            UpdaterSecurityPolicy.isAllowedRedirectTarget(
                "https://objects.githubusercontent.com/github-production-release-asset-123/file.apk",
                owner,
                repo
            )
        )
        assertFalse(
            UpdaterSecurityPolicy.isAllowedRedirectTarget(
                "http://objects.githubusercontent.com/file.apk",
                owner,
                repo
            )
        )
        assertFalse(
            UpdaterSecurityPolicy.isAllowedRedirectTarget(
                "https://malicious-cdn.net/file.apk",
                owner,
                repo
            )
        )
    }

    @Test
    fun sha256ParsingAndComputation_matchesExpectedDigest() {
        val sampleText = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855  Framewise-v1.0.1.apk\n"
        val parsed = UpdaterSecurityPolicy.parseSha256Hex(sampleText)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", parsed)

        assertNull(UpdaterSecurityPolicy.parseSha256Hex("not-a-hash"))

        val tempFile = File.createTempFile("framewise-sha-test", ".bin")
        try {
            tempFile.writeText("Framewise")
            val digest = UpdaterSecurityPolicy.computeSha256Hex(tempFile)
            assertEquals(64, digest.length)
            assertEquals(digest, UpdaterSecurityPolicy.computeSha256Hex(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun parseReleaseJson_rejectsUntrustedAssetUrlsAndParsesValidRelease() {
        val validJson = """
            {
              "tag_name": "v1.2.0",
              "body": "CAS sharpness tuning",
              "html_url": "https://github.com/$owner/$repo/releases/tag/v1.2.0",
              "assets": [
                {
                  "name": "evil.apk",
                  "browser_download_url": "https://evil.com/evil.apk",
                  "size": 12345
                },
                {
                  "name": "Framewise-v1.2.0.apk",
                  "browser_download_url": "https://github.com/$owner/$repo/releases/download/v1.2.0/Framewise-v1.2.0.apk",
                  "size": 987654
                },
                {
                  "name": "Framewise-v1.2.0.apk.sha256",
                  "browser_download_url": "https://github.com/$owner/$repo/releases/download/v1.2.0/Framewise-v1.2.0.apk.sha256",
                  "size": 90
                }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseUpdater.parseReleaseJson(validJson, owner, repo)
        assertNotNull(release)
        assertEquals("Framewise-v1.2.0.apk", release!!.apkName)
        assertEquals(SemanticVersion(1, 2, 0), release.version)
        assertNotNull(release.sha256Url)
    }
}
