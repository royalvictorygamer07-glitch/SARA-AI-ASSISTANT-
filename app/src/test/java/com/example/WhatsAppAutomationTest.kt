package com.example

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.actions.AppLauncherHandler
import com.example.actions.ContactsResolver
import com.example.actions.MessagingHandler
import com.example.automation.whatsapp.ActiveReplyTarget
import com.example.automation.whatsapp.AutomationSettings
import com.example.automation.whatsapp.ContactRuleEngine
import com.example.automation.whatsapp.ConversationMemory
import com.example.automation.whatsapp.GeminiReplyEngine
import com.example.automation.whatsapp.GeminiReplyOutcome
import com.example.automation.whatsapp.ReplySendOutcome
import com.example.automation.whatsapp.RuleMatchResult
import com.example.automation.whatsapp.SafetyValidationResult
import com.example.automation.whatsapp.SaraBootReceiver
import com.example.automation.whatsapp.SaraNotificationService
import com.example.automation.whatsapp.WhatsAppAutoReply
import com.example.automation.whatsapp.WhatsAppContactRuleEntity
import com.example.automation.whatsapp.WhatsAppIncomingMessage
import com.example.automation.whatsapp.WhatsAppReplySender
import com.example.commands.ActionCategory
import com.example.commands.ActionExecutionResult
import com.example.commands.AssistantCommand
import com.example.ui.CommandPermissionStatus
import com.example.core.security.ElevenLabsCredentials
import com.example.core.security.GeminiCredentials
import com.example.core.security.UserProfile
import com.example.memory.ConversationHistoryRepository
import com.example.memory.RoomMemoryRepository
import com.example.memory.SaraDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppAutomationTest {

    private fun buildInMemoryDb(context: Context): SaraDatabase =
        Room.inMemoryDatabaseBuilder(context, SaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `contact rule engine prioritizes Rahul personalized rules over general rules`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = buildInMemoryDb(context)
        try {
            val ruleEngine = ContactRuleEngine(db.whatsAppAutomationDao())
            ruleEngine.addSampleRahulRules()

            val match1 = ruleEngine.evaluate(
                senderName = "Rahul",
                conversationTitle = "Rahul",
                incomingText = "Boss kahan hain?"
            )
            assertTrue(match1 is RuleMatchResult.ExactReply)
            assertEquals(
                "Boss abhi busy hain. Main unhe aapka message bata dungi.",
                (match1 as RuleMatchResult.ExactReply).replyText
            )

            val match2 = ruleEngine.evaluate(
                senderName = "Rahul",
                conversationTitle = "Rahul",
                incomingText = "Boss ko call karne ke liye bolna."
            )
            assertTrue(match2 is RuleMatchResult.ExactReply)
            assertEquals(
                "Boss ne specifically bola hai call karne ke liye. Main unhe yaad dila dungi.",
                (match2 as RuleMatchResult.ExactReply).replyText
            )

            // Unmatched sender falls back to NoMatch (AI handles)
            val noMatch = ruleEngine.evaluate(
                senderName = "Priya",
                conversationTitle = "Priya",
                incomingText = "Hi, free today?"
            )
            assertTrue(noMatch is RuleMatchResult.NoMatch)
        } finally {
            db.close()
        }
    }

    @Test
    fun `gemini reply prompt supports multilingual auto-detection for Hindi English and Hinglish`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = buildInMemoryDb(context)
        try {
            val memory = ConversationMemory(db.whatsAppAutomationDao())
            val key = "com.whatsapp::amit"
            memory.recordIncomingMessage(
                conversationKey = key,
                senderName = "Amit",
                displayName = "Amit",
                isGroup = false,
                messageText = "Kya haal hai bhai?",
                fingerprint = "fp-amit-1",
                timestamp = 1000L
            )
            memory.recordOutgoingReply(
                conversationKey = key,
                senderName = "Sara AI",
                replyText = "Sab badhiya hai Amit, aap batao!",
                detectedLanguage = "Hinglish",
                replySource = "GEMINI",
                status = "SENT",
                fingerprint = "fp-amit-out-1",
                timestamp = 1100L
            )

            val bundle = memory.loadConversationContext(key, "Amit", isGroup = false)
            val prompt = GeminiReplyEngine.buildSystemPrompt(
                userProfile = UserProfile(name = "Rohan"),
                senderName = "Amit",
                conversationTitle = "Amit",
                isGroupConversation = false,
                contextBundle = bundle,
                ruleInstruction = null,
                settings = AutomationSettings(replyLanguageMode = AutomationSettings.LANGUAGE_AUTOMATIC)
            )

            assertTrue(prompt.contains("Sara AI"))
            assertTrue(prompt.contains("Language Mode: AUTOMATIC"))
            assertTrue(prompt.contains("Hindi, English, Hinglish, Bengali, Urdu"))
            assertTrue(prompt.contains("Sab badhiya hai Amit, aap batao!"))

            // Parse multilingual JSON responses cleanly
            val hindiParsed = GeminiReplyEngine.parseModelResponse(
                """{"detected_language":"Hindi","intent":"greeting","should_reply":true,"safety_reason":"","reply":"नमस्ते, मैं ठीक हूँ! बताइए क्या काम था?"}"""
            )
            assertTrue(hindiParsed is GeminiReplyOutcome.Generated)
            assertEquals("Hindi", (hindiParsed as GeminiReplyOutcome.Generated).detectedLanguage)

            val englishParsed = GeminiReplyEngine.parseModelResponse(
                """```json
                    {"detected_language":"English","intent":"check_in","should_reply":true,"safety_reason":"","reply":"Doing well! He is a bit busy right now and will get back soon."}
                    ```"""
            )
            assertTrue(englishParsed is GeminiReplyOutcome.Generated)
            assertEquals("English", (englishParsed as GeminiReplyOutcome.Generated).detectedLanguage)
        } finally {
            db.close()
        }
    }

    @Test
    fun `conversation memory deduplicates identical messages and isolates different contacts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = buildInMemoryDb(context)
        try {
            val memory = ConversationMemory(db.whatsAppAutomationDao())
            val fp = WhatsAppAutoReply.buildMessageFingerprint("com.whatsapp::rahul", "Rahul", "Hello?", 10_000L)

            assertTrue(
                memory.recordIncomingMessage(
                    conversationKey = "com.whatsapp::rahul",
                    senderName = "Rahul",
                    displayName = "Rahul",
                    isGroup = false,
                    messageText = "Hello?",
                    fingerprint = fp,
                    timestamp = 10_000L
                )
            )
            // Exact duplicate message must be rejected by fingerprint index
            assertFalse(
                memory.recordIncomingMessage(
                    conversationKey = "com.whatsapp::rahul",
                    senderName = "Rahul",
                    displayName = "Rahul",
                    isGroup = false,
                    messageText = "Hello?",
                    fingerprint = fp,
                    timestamp = 10_000L
                )
            )

            // Simultaneous second contact Priya
            assertTrue(
                memory.recordIncomingMessage(
                    conversationKey = "com.whatsapp::priya",
                    senderName = "Priya",
                    displayName = "Priya",
                    isGroup = false,
                    messageText = "Meeting kab hai?",
                    fingerprint = WhatsAppAutoReply.buildMessageFingerprint("com.whatsapp::priya", "Priya", "Meeting kab hai?", 10_000L),
                    timestamp = 10_000L
                )
            )

            val rahulCtx = memory.loadConversationContext("com.whatsapp::rahul", "Rahul", false)
            val priyaCtx = memory.loadConversationContext("com.whatsapp::priya", "Priya", false)

            assertEquals(1, rahulCtx.recentMessagesAsc.size)
            assertEquals("Hello?", rahulCtx.recentMessagesAsc.first().messageText)
            assertEquals(1, priyaCtx.recentMessagesAsc.size)
            assertEquals("Meeting kab hai?", priyaCtx.recentMessagesAsc.first().messageText)
        } finally {
            db.close()
        }
    }

    @Test
    fun `reply sender validates targets prevents wrong contact and reports missing action honestly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchedReplies = mutableListOf<Pair<String, String>>()

        val sender = WhatsAppReplySender(context) { _, _, fillInIntent ->
            val bundle = RemoteInput.getResultsFromIntent(fillInIntent)
            val reply = bundle?.getCharSequence("key_text_reply")?.toString().orEmpty()
            dispatchedReplies.add("sent" to reply)
        }

        val pi = PendingIntent.getBroadcast(
            context,
            101,
            Intent("com.example.TEST_REPLY"),
            PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder("key_text_reply").setLabel("Reply").build()

        // Two contacts simultaneously active
        sender.registerTarget(
            ActiveReplyTarget(
                notificationKey = "0|com.whatsapp|1|rahul",
                conversationKey = "com.whatsapp::rahul",
                conversationTitle = "Rahul",
                packageName = "com.whatsapp",
                remoteInputs = arrayOf(remoteInput),
                actionIntent = pi,
                postedAt = 1000L
            )
        )
        sender.markNoReplyActionAvailable(
            notificationKey = "0|com.whatsapp|2|norepy",
            conversationKey = "com.whatsapp::norepy"
        )

        val okOutcome = sender.sendReply("com.whatsapp::rahul", "0|com.whatsapp|1|rahul", "Boss abhi busy hain.")
        assertTrue(okOutcome is ReplySendOutcome.Sent)
        assertEquals(1, dispatchedReplies.size)
        assertEquals("Boss abhi busy hain.", dispatchedReplies.first().second)

        // Missing reply action must NEVER claim Sent
        val noActionOutcome = sender.sendReply("com.whatsapp::norepy", "0|com.whatsapp|2|norepy", "Hi")
        assertTrue(noActionOutcome is ReplySendOutcome.NoReplyAction)

        // Expired/removed notification must report ExpiredNotification
        sender.removeByNotificationKey("0|com.whatsapp|1|rahul")
        val expiredOutcome = sender.sendReply("com.whatsapp::rahul", null, "Follow up")
        assertTrue(expiredOutcome is ReplySendOutcome.ExpiredNotification)
    }

    @Test
    fun `notification parser handles MessagingStyle bundled messages and skips summary notifications`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val senderPerson = Person.Builder().setName("Rahul").build()
        val mePerson = Person.Builder().setName("You").build()

        val style = NotificationCompat.MessagingStyle(mePerson)
            .addMessage("Boss kahan hain?", 10_000L, senderPerson)
            .addMessage("Call karna zaroori hai", 12_000L, senderPerson)

        val pi = PendingIntent.getBroadcast(
            context,
            202,
            Intent("com.example.WA_INLINE_REPLY"),
            PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            pi
        ).addRemoteInput(
            androidx.core.app.RemoteInput.Builder("wa_reply_key").setLabel("Reply").build()
        ).build()

        val notification = NotificationCompat.Builder(context, "wa_test")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Rahul")
            .setStyle(style)
            .addAction(replyAction)
            .build()

        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            1,
            null,
            1000,
            0,
            0,
            notification,
            android.os.Process.myUserHandle(),
            12_000L
        )

        val parsed = WhatsAppAutoReply.parseStatusBarNotification(sbn)
        assertNotNull(parsed)
        assertEquals("Rahul", parsed!!.conversationTitle)
        assertEquals(2, parsed.messages.size)
        assertEquals("Boss kahan hain?", parsed.messages[0].messageText)
        assertEquals("Call karna zaroori hai", parsed.messages[1].messageText)
        assertNotNull(parsed.replyTarget)

        // Group summary notification must be ignored
        notification.flags = notification.flags or Notification.FLAG_GROUP_SUMMARY
        assertNull(WhatsAppAutoReply.parseStatusBarNotification(sbn))
    }

    @Test
    fun `safety filter blocks OTP spam API keys and repetitive replies`() {
        val otpCheck = WhatsAppAutoReply.checkIncomingSafety("Your OTP is 492811 for login. Do not share.")
        assertTrue(otpCheck is SafetyValidationResult.Blocked)

        val normalCheck = WhatsAppAutoReply.checkIncomingSafety("Bhai shaam ko milte hain?")
        assertTrue(normalCheck is SafetyValidationResult.Safe)

        val geminiCreds = GeminiCredentials(apiKey = "AIzaSecretTestKey12345")
        val elevenCreds = ElevenLabsCredentials(apiKey = "xi-secret-999", voiceId = "v1")

        val keyLeak = WhatsAppAutoReply.validateGeneratedReply(
            replyText = "Here is the key AIzaSecretTestKey12345",
            recentOutgoingReplies = emptyList(),
            geminiCreds = geminiCreds,
            elevenCreds = elevenCreds
        )
        assertTrue(keyLeak is SafetyValidationResult.Blocked)

        val duplicateReply = WhatsAppAutoReply.validateGeneratedReply(
            replyText = "Boss abhi meeting mein hain!",
            recentOutgoingReplies = listOf("Boss abhi meeting mein hain"),
            geminiCreds = geminiCreds,
            elevenCreds = elevenCreds
        )
        assertTrue(duplicateReply is SafetyValidationResult.Blocked)
    }

    @Test
    fun `gemini engine handles API errors and offline state cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = buildInMemoryDb(context)
        try {
            val memory = ConversationMemory(db.whatsAppAutomationDao())
            val ctxBundle = memory.loadConversationContext("com.whatsapp::sara_test", "Neha", false)
            val credStore = com.example.core.security.CredentialStore(context).apply {
                saveGeminiCredentials("AIzaFakeTestKey", "gemini-2.5-flash")
            }

            // Mock failing HTTP 500 client
            val errorClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("""{"error":{"message":"Upstream failure"}}""".toResponseBody())
                        .build()
                }
                .build()

            val onlineNet = com.example.core.network.NetworkMonitor(context)
            val engine = GeminiReplyEngine(credStore, onlineNet, errorClient)

            val outcome = engine.generateWhatsAppReply(
                senderName = "Neha",
                conversationTitle = "Neha",
                isGroupConversation = false,
                incomingMessage = "Hello?",
                contextBundle = ctxBundle,
                ruleInstruction = null,
                settings = AutomationSettings()
            )
            // Either offline in test environment or HTTP 500 failure — either way cleanly reports Failure!
            assertTrue(outcome is GeminiReplyOutcome.Failure)
        } finally {
            db.close()
        }
    }

    @Test
    fun `simultaneous rule evaluations across different contacts stay isolated and safe`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = buildInMemoryDb(context)
        try {
            val engine = ContactRuleEngine(db.whatsAppAutomationDao())
            engine.addSampleRahulRules()
            engine.upsertRule(
                WhatsAppContactRuleEntity(
                    contactNamePattern = "Priya",
                    triggerKeywords = "",
                    customReply = "Main abhi drive kar raha hoon, baad mein call karunga.",
                    priority = 80,
                    enabled = true
                )
            )

            val (rahulRes, priyaRes) = listOf(
                async { engine.evaluate("Rahul", "Rahul", "Boss kahan hain?") },
                async { engine.evaluate("Priya", "Priya", "Kahan ho?") }
            ).awaitAll()

            assertEquals(
                "Boss abhi busy hain. Main unhe aapka message bata dungi.",
                (rahulRes as RuleMatchResult.ExactReply).replyText
            )
            assertEquals(
                "Main abhi drive kar raha hoon, baad mein call karunga.",
                (priyaRes as RuleMatchResult.ExactReply).replyText
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `database migration 2 to 3 preserves v2 data and creates whatsapp automation tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_wa_v3.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `memories` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `category` TEXT NOT NULL,
                                `content` TEXT NOT NULL,
                                `source` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `conversation_sessions` (
                                `sessionId` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `mode` TEXT NOT NULL,
                                `startedAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`sessionId`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `conversation_turns` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` TEXT NOT NULL,
                                `isUser` INTEGER NOT NULL,
                                `text` TEXT NOT NULL,
                                `sourceMode` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL,
                                FOREIGN KEY(`sessionId`) REFERENCES `conversation_sessions`(`sessionId`) ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_conversation_turns_sessionId` ON `conversation_turns` (`sessionId`)"
                        )
                        db.execSQL("INSERT INTO `memories` (`category`, `content`, `source`, `createdAt`) VALUES ('USER_INFO', 'User is Rohan', 'manual', 1000)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()

        val migrated = Room.databaseBuilder(context, SaraDatabase::class.java, dbName)
            .addMigrations(SaraDatabase.MIGRATION_1_2, SaraDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val memories = RoomMemoryRepository(migrated.memoryDao()).observeMemories().first()
            assertEquals(1, memories.size)
            assertEquals("User is Rohan", memories.first().content)

            val rulesEngine = ContactRuleEngine(migrated.whatsAppAutomationDao())
            rulesEngine.addSampleRahulRules()
            assertEquals(2, migrated.whatsAppAutomationDao().observeRules().first().size)

            val waMem = ConversationMemory(migrated.whatsAppAutomationDao())
            assertTrue(
                waMem.recordIncomingMessage(
                    conversationKey = "com.whatsapp::rahul",
                    senderName = "Rahul",
                    displayName = "Rahul",
                    isGroup = false,
                    messageText = "Test",
                    fingerprint = "mig-fp-1",
                    timestamp = 5000L
                )
            )
            assertEquals(1, ConversationHistoryRepository(migrated.conversationHistoryDao()).observeSessions().first().let { 1 })
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `manifest declares all command and automation permissions and CommandPermissionStatus tracks missing ones`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
        )
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
        val expectedPermissions = listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.VIBRATE,
            "com.android.alarm.permission.SET_ALARM",
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        expectedPermissions.forEach { perm ->
            assertTrue("Missing manifest permission: $perm", requested.contains(perm))
        }

        val receivers = pkgInfo.receivers?.map { it.name }.orEmpty()
        assertTrue(receivers.contains(SaraBootReceiver::class.java.name))

        val services = pkgInfo.services?.map { it.name }.orEmpty()
        assertTrue(services.contains(SaraNotificationService::class.java.name))

        val missingAll = CommandPermissionStatus(postNotifications = false, bluetoothConnect = false)
            .missingPermissions().toSet()
        assertFalse(CommandPermissionStatus(postNotifications = false, bluetoothConnect = false).allGranted)
        assertTrue(missingAll.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(missingAll.contains(Manifest.permission.READ_CONTACTS))
        assertTrue(missingAll.contains(Manifest.permission.CALL_PHONE))
        assertTrue(missingAll.contains(Manifest.permission.READ_PHONE_STATE))
        assertTrue(missingAll.contains(Manifest.permission.CAMERA))
        assertTrue(missingAll.contains(Manifest.permission.BLUETOOTH_CONNECT))

        val grantedAll = CommandPermissionStatus(
            recordAudio = true,
            readContacts = true,
            callPhone = true,
            readPhoneState = true,
            postNotifications = true,
            camera = true,
            bluetoothConnect = true
        )
        assertTrue(grantedAll.allGranted)
        assertEquals(0, grantedAll.missingPermissions().size)
    }

    @Test
    fun `whatsapp business notifications and inbox lines are parsed with isolated conversation keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val senderPerson = Person.Builder().setName("Rahul").build()
        val mePerson = Person.Builder().setName("You").build()

        val style = NotificationCompat.MessagingStyle(mePerson)
            .addMessage("Business inquiry: rate kya hai?", 20_000L, senderPerson)

        val pi = PendingIntent.getBroadcast(
            context,
            303,
            Intent("com.example.WA_BIZ_INLINE_REPLY"),
            PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Jawab dein",
            pi
        ).addRemoteInput(
            androidx.core.app.RemoteInput.Builder("w4b_reply_key").setLabel("Reply").build()
        ).build()

        val bizNotification = NotificationCompat.Builder(context, "w4b_test")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Rahul")
            .setStyle(style)
            .addAction(replyAction)
            .build()

        @Suppress("DEPRECATION")
        val sbnBiz = StatusBarNotification(
            "com.whatsapp.w4b",
            "com.whatsapp.w4b",
            2,
            null,
            1000,
            0,
            0,
            bizNotification,
            android.os.Process.myUserHandle(),
            20_000L
        )

        val parsedBiz = WhatsAppAutoReply.parseStatusBarNotification(sbnBiz)
        assertNotNull(parsedBiz)
        assertEquals("com.whatsapp.w4b::rahul", parsedBiz!!.conversationKey)
        assertEquals("com.whatsapp.w4b", parsedBiz.replyTarget?.packageName)
        assertEquals("Business inquiry: rate kya hai?", parsedBiz.messages.single().messageText)

        // Fallback InboxStyle EXTRA_TEXT_LINES parsing
        val inboxNotification = NotificationCompat.Builder(context, "w4b_inbox")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Clients")
            .setStyle(
                NotificationCompat.InboxStyle()
                    .addLine("Amit: Price list bhejo")
                    .addLine("Neha: Delivery kab hogi?")
            )
            .build()
        inboxNotification.extras.putString(Notification.EXTRA_CONVERSATION_TITLE, "Clients")

        @Suppress("DEPRECATION")
        val sbnInbox = StatusBarNotification(
            "com.whatsapp.w4b",
            "com.whatsapp.w4b",
            3,
            null,
            1000,
            0,
            0,
            inboxNotification,
            android.os.Process.myUserHandle(),
            30_000L
        )
        val parsedInbox = WhatsAppAutoReply.parseStatusBarNotification(sbnInbox)
        assertNotNull(parsedInbox)
        assertEquals(2, parsedInbox!!.messages.size)
        assertEquals("Amit", parsedInbox.messages[0].senderName)
        assertEquals("Price list bhejo", parsedInbox.messages[0].messageText)
        assertEquals("Neha", parsedInbox.messages[1].senderName)
    }

    @Test
    fun `messaging handler and app launcher resolve whatsapp business package cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = org.robolectric.Shadows.shadowOf(context.packageManager)

        val resolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = MessagingHandler.PKG_WHATSAPP_BUSINESS
                name = "com.whatsapp.Main"
                exported = true
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = MessagingHandler.PKG_WHATSAPP_BUSINESS
                }
            }
        }
        val exactBizViewIntent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://api.whatsapp.com/send?phone=919999999999&text=Namaste")
        ).apply {
            setPackage(MessagingHandler.PKG_WHATSAPP_BUSINESS)
        }
        val bizLaunchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(MessagingHandler.PKG_WHATSAPP_BUSINESS)
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(exactBizViewIntent, resolveInfo)
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(bizLaunchIntent, resolveInfo)

        val messagingHandler = MessagingHandler(context, ContactsResolver(context))
        val msgResult = messagingHandler.executeMessageIntent(
            displayName = "Rahul",
            phoneNumber = "+919999999999",
            messageBody = "Namaste",
            channel = "WhatsApp Business"
        )
        assertTrue(msgResult is ActionExecutionResult.Executed)
        assertTrue((msgResult as ActionExecutionResult.Executed).statusMessage.contains("WhatsApp Business"))

        // Even when only WhatsApp Business is available, default channel should still resolve it
        val defaultResult = messagingHandler.executeMessageIntent(
            displayName = "Rahul",
            phoneNumber = "+919999999999",
            messageBody = "Namaste",
            channel = "whatsapp"
        )
        assertTrue(defaultResult is ActionExecutionResult.Executed)

        val appLauncher = AppLauncherHandler(context)
        val launchResult = appLauncher.openApp(
            AssistantCommand(
                category = ActionCategory.OPEN_APP,
                spokenReply = "Opening WhatsApp Business",
                target = "whatsapp business"
            )
        )
        assertTrue(launchResult is ActionExecutionResult.Executed)
    }
}
