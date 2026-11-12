package com.example.util

import com.example.data.model.AppItem

/**
 * Parses spoken voice commands (Spanish/English) for searching or launching TV apps.
 */
object VoiceCommandParser {

    data class ParsedCommand(
        val rawText: String,
        val searchQuery: String,
        val isExplicitLaunch: Boolean
    )

    private val launchPrefixes = listOf(
        "abrir ",
        "abre ",
        "iniciar ",
        "inicia ",
        "ejecutar ",
        "ejecuta ",
        "open ",
        "launch ",
        "start "
    )

    private val searchPrefixes = listOf(
        "buscar ",
        "busca ",
        "search for ",
        "search ",
        "find "
    )

    fun parse(rawText: String): ParsedCommand {
        val cleaned = rawText.trim().trimEnd('.', ',', '!', '?')
        val lower = cleaned.lowercase()

        for (prefix in launchPrefixes) {
            if (lower.startsWith(prefix)) {
                val query = cleaned.substring(prefix.length).trim()
                return ParsedCommand(cleaned, query, isExplicitLaunch = true)
            }
        }

        for (prefix in searchPrefixes) {
            if (lower.startsWith(prefix)) {
                val query = cleaned.substring(prefix.length).trim()
                return ParsedCommand(cleaned, query, isExplicitLaunch = false)
            }
        }

        return ParsedCommand(cleaned, cleaned, isExplicitLaunch = false)
    }

    fun findBestMatches(apps: List<AppItem>, query: String): List<AppItem> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()

        val exactMatches = apps.filter { it.label.equals(q, ignoreCase = true) }
        if (exactMatches.isNotEmpty()) return exactMatches

        val startsWithMatches = apps.filter { it.label.startsWith(q, ignoreCase = true) }
        val containsMatches = apps.filter {
            !it.label.startsWith(q, ignoreCase = true) &&
                (it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true))
        }
        return startsWithMatches + containsMatches
    }
}
