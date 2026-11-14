package com.adbhost.app.engine

data class InstallArgs(
    val apkPaths: List<String>,
    val allowTest: Boolean,
    val allowDowngrade: Boolean,
    val grantRuntimePermissions: Boolean,
    val replace: Boolean,
    val parseError: String?,
)

object InstallArgsParser {

    private val supportedFlags = setOf("-r", "-t", "-d", "-g")

    fun parse(command: String): InstallArgs {
        val body = command.trim()
            .removePrefix("cmd package install")
            .removePrefix("pm install")
            .trim()
        val tokens = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val apkPaths = ArrayList<String>()
        var allowTest = false
        var allowDowngrade = false
        var grantRuntimePermissions = false
        var streamingSize = false
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token == "-S" -> {
                    streamingSize = true
                    index++
                }
                token in supportedFlags -> {
                    when (token) {
                        "-t" -> allowTest = true
                        "-d" -> allowDowngrade = true
                        "-g" -> grantRuntimePermissions = true
                    }
                }
                token.startsWith("-") && token.length > 1 && token[1].isDigit() -> index++
                token.startsWith("-") -> {
                    val long = token.removePrefix("-")
                    long.forEach { flag ->
                        when (flag) {
                            'r' -> Unit
                            't' -> allowTest = true
                            'd' -> allowDowngrade = true
                            'g' -> grantRuntimePermissions = true
                            'S' -> streamingSize = true
                            else -> return failed("unsupported flag $token")
                        }
                    }
                }
                else -> apkPaths.add(token)
            }
            index++
        }
        if (apkPaths.isEmpty() && !streamingSize) return failed("no apk path")
        return InstallArgs(apkPaths, allowTest, allowDowngrade, grantRuntimePermissions, true, null)
    }

    private fun failed(reason: String) = InstallArgs(emptyList(), false, false, false, false, reason)
}
