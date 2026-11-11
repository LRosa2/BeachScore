package ch.beachscore.app.history

import java.util.Locale

sealed interface NameCheck {
    data class Ok(val name: String) : NameCheck
    data object Empty : NameCheck
    data object TooLong : NameCheck
    data class Duplicate(val existing: Player) : NameCheck
}

/**
 * Player-name rules: trimmed, inner whitespace collapsed, not empty, at most [MAX_LENGTH]
 * characters, unique among active players ignoring case. Archived names are free again;
 * [archivedWithName] lets "add" bring such a player back instead of creating a twin.
 */
object PlayerNames {
    const val MAX_LENGTH = 30

    fun normalize(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun key(raw: String): String = normalize(raw).lowercase(Locale.ROOT)

    fun check(raw: String, players: List<Player>, renaming: Long? = null): NameCheck {
        val name = normalize(raw)
        if (name.isEmpty()) return NameCheck.Empty
        if (name.length > MAX_LENGTH) return NameCheck.TooLong
        val key = name.lowercase(Locale.ROOT)
        val clash = players.firstOrNull { !it.archived && it.id != renaming && key(it.name) == key }
        return if (clash != null) NameCheck.Duplicate(clash) else NameCheck.Ok(name)
    }

    fun archivedWithName(raw: String, players: List<Player>): Player? {
        val key = key(raw)
        return players.firstOrNull { it.archived && key(it.name) == key }
    }
}
