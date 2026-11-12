plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

// Automatically decode debug.keystore from debug.keystore.base64 on fresh CI/Git checkout if missing
val debugKeystore = file("${rootDir}/debug.keystore")
val debugKeystoreBase64 = file("${rootDir}/debug.keystore.base64")
if (!debugKeystore.exists() && debugKeystoreBase64.exists()) {
  try {
    val cleanBase64 = debugKeystoreBase64.readText().replace("\\s".toRegex(), "")
    val decoded = java.util.Base64.getDecoder().decode(cleanBase64)
    debugKeystore.writeBytes(decoded)
  } catch (e: Exception) {
    logger.warn("Could not auto-restore debug.keystore: ${e.message}")
  }
}

