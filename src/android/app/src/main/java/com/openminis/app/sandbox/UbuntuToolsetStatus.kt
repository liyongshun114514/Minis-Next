package com.openminis.app.sandbox

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Local readiness check. No process execution, networking, or package install. */
object UbuntuToolsetStatus {
    const val MANIFEST = "usr/local/share/minis/toolset-manifest.json"
    const val OFFICIAL_BASE_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    val requiredCommands = listOf("bash", "python3", "git", "curl", "wget", "ssh", "rg", "jq", "unzip", "xz")

    fun problem(root: File): String? = try {
        inspect(root)
    } catch (e: Exception) {
        "Cannot verify preinstalled toolset: ${e.message ?: e.javaClass.simpleName}"
    }

    fun isReady(root: File): Boolean = problem(root) == null

    private fun inspect(root: File): String? {
        val manifestFile = File(root, MANIFEST)
        if (!manifestFile.isFile) return "Preinstalled toolset manifest is missing"
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        if (manifest["schema"]?.jsonPrimitive?.intOrNull != 1) return "Unsupported toolset schema"
        if (manifest["distro"]?.jsonPrimitive?.content != "ubuntu24.04" ||
            manifest["arch"]?.jsonPrimitive?.content != "arm64" ||
            manifest["official_base_sha256"]?.jsonPrimitive?.content != OFFICIAL_BASE_SHA256) {
            return "Unexpected toolset identity"
        }
        val packages = manifest["packages"]?.jsonObject ?: return "Package manifest is missing"
        if (!SandboxDistribution.BOOTSTRAP_PACKAGES.all { packages[it]?.jsonPrimitive?.content?.isNotBlank() == true }) {
            return "Package manifest does not contain the complete default toolset"
        }
        if (!File(root, SandboxDistribution.BOOTSTRAP_DONE_MARKER).isFile) return "Toolset completion marker is missing"
        val statusFile = File(root, "var/lib/dpkg/status")
        if (!statusFile.isFile) return "Package database is missing"
        val installed = mutableSetOf<String>()
        var name = ""
        var status = ""
        var version = ""
        fun finish() {
            if (name.isNotBlank() && status == "install ok installed" && version.isNotBlank()) {
                installed += name.substringBefore(':')
            }
            name = ""; status = ""; version = ""
        }
        statusFile.useLines { lines ->
            lines.forEach { line ->
                when {
                    line.isBlank() -> finish()
                    line.startsWith("Package: ") -> name = line.substringAfter("Package: ").trim()
                    line.startsWith("Status: ") -> status = line.substringAfter("Status: ").trim()
                    line.startsWith("Version: ") -> version = line.substringAfter("Version: ").trim()
                }
            }
        }
        finish()
        val missing = SandboxDistribution.BOOTSTRAP_PACKAGES.filterNot { it in installed }
        if (missing.isNotEmpty()) return "Packages are not fully configured: ${missing.joinToString()}"
        val missingCommands = requiredCommands.filterNot { File(root, "usr/bin/$it").isFile }
        if (missingCommands.isNotEmpty()) return "Preinstalled commands are missing: ${missingCommands.joinToString()}"
        return null
    }
}
