package ch.beachscore.app.history

import ch.beachscore.app.score.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LineupAndNamesTest {

    @Test
    fun `toggle adds, removes and caps a team at two`() {
        var l = Lineup()
        l = l.toggle(Team.BLUE, 1).toggle(Team.BLUE, 2)
        assertEquals(listOf(1L, 2L), l.blue)
        assertEquals(l, l.toggle(Team.BLUE, 3), "a third player is ignored")
        assertEquals(listOf(2L), l.toggle(Team.BLUE, 1).blue)
    }

    @Test
    fun `a player cannot be on both teams`() {
        val l = Lineup(blue = listOf(1, 2), red = listOf(3)).toggle(Team.RED, 1)
        assertEquals(listOf(2L), l.blue)
        assertEquals(listOf(3L, 1L), l.red)
    }

    @Test
    fun `moving to a full team does nothing`() {
        val l = Lineup(blue = listOf(1), red = listOf(2, 3))
        assertEquals(l, l.toggle(Team.RED, 1))
    }

    @Test
    fun `removing players keeps the rest`() {
        assertEquals(Lineup(blue = listOf(2), red = listOf(3)), Lineup(blue = listOf(1, 2), red = listOf(3, 4)).without(setOf(1, 4)))
    }

    private val players = listOf(
        Player(1, "Anna", archived = false),
        Player(2, "Ben Müller", archived = false),
        Player(3, "Carla", archived = true),
    )

    @Test
    fun `names are trimmed and inner spaces collapsed`() {
        assertEquals(NameCheck.Ok("Dora Meier"), PlayerNames.check("  Dora   Meier ", players))
    }

    @Test
    fun `empty and too long names are rejected`() {
        assertEquals(NameCheck.Empty, PlayerNames.check("   ", players))
        assertEquals(NameCheck.TooLong, PlayerNames.check("x".repeat(PlayerNames.MAX_LENGTH + 1), players))
        assertEquals(NameCheck.Ok("x".repeat(PlayerNames.MAX_LENGTH)), PlayerNames.check("x".repeat(PlayerNames.MAX_LENGTH), players))
    }

    @Test
    fun `duplicates ignore case and surrounding spaces`() {
        assertEquals(NameCheck.Duplicate(players[0]), PlayerNames.check(" anna ", players))
        assertEquals(NameCheck.Duplicate(players[1]), PlayerNames.check("BEN  MÜLLER", players))
    }

    @Test
    fun `renaming a player to a new spelling of its own name is fine`() {
        assertEquals(NameCheck.Ok("ANNA"), PlayerNames.check("ANNA", players, renaming = 1))
        assertEquals(NameCheck.Duplicate(players[1]), PlayerNames.check("ben müller", players, renaming = 1))
    }

    @Test
    fun `archived names are free but can be revived`() {
        assertEquals(NameCheck.Ok("carla"), PlayerNames.check("carla", players))
        assertEquals(players[2], PlayerNames.archivedWithName(" CARLA", players))
        assertEquals(null, PlayerNames.archivedWithName("Anna", players))
    }
}
