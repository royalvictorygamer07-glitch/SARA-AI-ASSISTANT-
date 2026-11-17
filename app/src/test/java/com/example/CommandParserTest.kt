package com.example

import com.example.commands.ActionCategory
import com.example.commands.CommandParser
import com.example.memory.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    @Test
    fun `parses structured whatsapp command json`() {
        val raw = """
            ```json
            {
              "action": "SEND_MESSAGE",
              "reply": "Rahul ko WhatsApp message bhejne ke liye confirm kijiye, Sir.",
              "target": "Rahul",
              "message": "Main 10 minute mein aa raha hoon",
              "channel": "whatsapp",
              "requires_confirmation": true,
              "memory_note": "User frequently messages Rahul",
              "memory_category": "RELATIONSHIP"
            }
            ```
        """.trimIndent()

        val parsed = CommandParser.parseModelOutput(raw)
        assertEquals(ActionCategory.SEND_MESSAGE, parsed.category)
        assertEquals("Rahul", parsed.target)
        assertEquals("Main 10 minute mein aa raha hoon", parsed.messageBody)
        assertEquals("whatsapp", parsed.channel)
        assertTrue(parsed.requiresConfirmation)
        assertEquals(MemoryCategory.RELATIONSHIP, parsed.memoryCategory)
    }

    @Test
    fun `falls back to CHAT on plain text`() {
        val parsed = CommandParser.parseModelOutput("Good evening, Sir.")
        assertEquals(ActionCategory.CHAT, parsed.category)
        assertEquals("Good evening, Sir.", parsed.spokenReply)
        assertFalse(parsed.requiresConfirmation)
    }

    @Test
    fun `recognizes english and hinglish voice confirmations`() {
        assertTrue(CommandParser.isAffirmativeConfirmation("Yes"))
        assertTrue(CommandParser.isAffirmativeConfirmation("Ha kar do"))
        assertTrue(CommandParser.isNegativeConfirmation("Nahi cancel karo"))
        assertFalse(CommandParser.isAffirmativeConfirmation("Nahi"))
    }
}
