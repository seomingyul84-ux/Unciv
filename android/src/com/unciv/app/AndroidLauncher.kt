package com.unciv.app

import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.unciv.logic.files.UncivFiles
import com.unciv.ui.components.fonts.Fonts
import com.unciv.utils.Display
import com.unciv.utils.Log
import java.io.File
import java.lang.Exception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// [추가] Firebase 라이브러리
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

open class AndroidLauncher : AndroidApplication() {

    private var game: AndroidGame? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup Android logging
        Log.backend = AndroidLogBackend(this)

        // Setup Android display
        val displayImpl = AndroidDisplay(this)
        Display.platform = displayImpl

        // Setup Android fonts
        Fonts.fontImplementation = AndroidFont()

        // Setup Android custom saver-loader
        UncivFiles.saverLoader = AndroidSaverLoader(this)
        UncivFiles.preferExternalStorage = true

        val settings = UncivFiles.getSettingsForPlatformLaunchers(filesDir.path)
        val config = AndroidApplicationConfiguration().apply { useImmersiveMode = settings.androidHideSystemUi }

        // Setup orientation, immersive mode and display cutout
        displayImpl.setOrientation(settings.displayOrientation)
        displayImpl.setCutoutFromUiThread(settings.androidCutout)

        // Create notification channels for Multiplayer notificator
        MultiplayerTurnCheckWorker.createNotificationChannels(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            copyMods()
        }

        game = AndroidGame(this)
        initialize(game, config)

        game!!.setDeepLinkedGame(intent)
        game!!.addScreenObscuredListener()

        // ✨ [추가] 앱 시작 시 Firebase에서 데이터 불러오기
        loadFromFirebase()
    }

    // ✨ [추가] Firebase 불러오기 로직
    private fun loadFromFirebase() {
        try {
            val database = Firebase.database.reference
            database.child("saves").child("last_game_state").get().addOnSuccessListener { snapshot ->
                val remoteData = snapshot.value as? String
                if (remoteData != null && game != null) {
                    game!!.loadGameFromString(remoteData)
                }
            }
        } catch (e: Exception) {
            Log.error("Firebase 로드 실패", e)
        }
    }

    /**
     * Copies mods from external data directory (where users can access) to the private one (where
     * libGDX reads from). Note: deletes all files currently in the private mod directory and
     * replaces them with the ones in the external folder!)
     */
    private fun copyMods() {
        // Mod directory in the internal app data (where Gdx.files.local looks)
        val internalModsDir = File("${filesDir.path}/mods")

        // Mod directory in the shared app data (where the user can see and modify)
        val externalPath = getExternalFilesDir(null)?.path ?: return
        val externalModsDir = File("$externalPath/mods")

        try { // Rarely we get a kotlin.io.AccessDeniedException, if so - no biggie
            // Copy external mod directory (with data user put in it) to internal (where it can be read)
            if (!externalModsDir.exists()) externalModsDir.mkdirs() // this can fail sometimes, which is why we check if it exists again in the next line
            if (externalModsDir.exists()) externalModsDir.copyRecursively(internalModsDir, true)
        } catch (ex: Exception) {}
    }

    override fun onPause() {
        // ✨ [추가] 앱 나갈 때 Firebase에 자동 저장
        saveToFirebase()

        val game = this.game!!
        if (game.isInitializedProxy()
                && game.gameInfo != null
                && game.settings.multiplayer.turnCheckerEnabled
                && game.files.getMultiplayerSaves().any()
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                MultiplayerTurnCheckWorker.startTurnChecker(
                    applicationContext,
                    game.files,
                    game.gameInfo!!,
                    game.settings.multiplayer
                )
            }
        }
        super.onPause()
    }

    // ✨ [추가] Firebase 저장 로직
    private fun saveToFirebase() {
        val currentGame = game ?: return
        try {
            if (currentGame.isInitializedProxy() && currentGame.gameInfo != null) {
                val gameData = currentGame.gameInfo!!.serialize()
                val database = Firebase.database.reference
                database.child("saves").child("last_game_state").setValue(gameData)
            }
        } catch (e: Exception) {
            Log.error("Firebase 저장 실패", e)
        }
    }

    override fun onResume() {
        try {
            WorkManager.getInstance(applicationContext).cancelAllWorkByTag(MultiplayerTurnCheckWorker.WORK_TAG)
            with(NotificationManagerCompat.from(this)) {
                cancel(MultiplayerTurnCheckWorker.NOTIFICATION_ID_INFO)
                cancel(MultiplayerTurnCheckWorker.NOTIFICATION_ID_SERVICE)
            }
        } catch (ignore: Exception) {
            /* Sometimes this fails for no apparent reason - the multiplayer checker failing to
               cancel should not be enough of a reason for the game to crash! */
        }
        super.onResume()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null)
            return
        game?.setDeepLinkedGame(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val saverLoader = UncivFiles.saverLoader as AndroidSaverLoader
        saverLoader.onActivityResult(requestCode, data)
        super.onActivityResult(requestCode, resultCode, data)
    }
}
