package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.MainActivity
import com.waypoint.app.planner.BlockSessionStore
import com.waypoint.app.planner.NamedBlockStore
import java.time.LocalDate

class BlockStartReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BLOCK_START    = "com.waypoint.app.BLOCK_START"
        const val ACTION_PROCEED_BLOCK  = "com.waypoint.app.PROCEED_BLOCK"
        const val EXTRA_BLOCK_ID        = "blockId"
        const val EXTRA_BLOCK_NAME      = "blockName"
        const val EXTRA_COLOR_ARGB      = "colorArgb"
        const val EXTRA_HAS_COLOR       = "hasColor"
        const val EXTRA_SCHEDULED_END_MS = "scheduledEndMs"
        private const val TAG           = "BlockStartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BLOCK_START  -> handleBlockStart(context, intent)
            ACTION_PROCEED_BLOCK -> handleProceed(context, intent)
        }
    }

    private fun handleBlockStart(context: Context, intent: Intent) {
        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID) ?: return
        val blockName = intent.getStringExtra(EXTRA_BLOCK_NAME) ?: return
        val scheduledEndMs = intent.getLongExtra(EXTRA_SCHEDULED_END_MS, 0L)

        val sessionStore = BlockSessionStore(context)
        if (sessionStore.loadCurrent()?.blockId == blockId) {
            AppLogger.i(TAG, "Already in session for $blockId, skipping notification")
            return
        }

        AppLogger.i(TAG, "Block start alarm fired: $blockId")
        BlockNotificationHelper.postBlockStartNotification(context, blockId, blockName, scheduledEndMs)
    }

    private fun handleProceed(context: Context, intent: Intent) {
        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID) ?: return
        val scheduledEndMs = intent.getLongExtra(EXTRA_SCHEDULED_END_MS, 0L)

        val namedBlockStore = NamedBlockStore(context)
        val block = namedBlockStore.loadBlock(blockId) ?: return

        BlockSessionStore(context).startSession(block, scheduledEndMs, LocalDate.now())
        BlockNotificationHelper.cancelBlockStartNotification(context, blockId)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("tab", 2)
        }
        context.startActivity(mainIntent)
        AppLogger.i(TAG, "Proceed: started session for $blockId, launching Tasks tab")
    }


}
