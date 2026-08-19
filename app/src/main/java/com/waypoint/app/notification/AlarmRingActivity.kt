package com.waypoint.app.notification

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmRingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContent {
            BackHandler { /* block back — alarm must be dismissed explicitly */ }
            AlarmRingScreen(
                onDismiss = { sendServiceAction(AlarmRingService.ACTION_DISMISS) },
                onSnooze  = { sendServiceAction(AlarmRingService.ACTION_SNOOZE) }
            )
        }
    }

    private fun sendServiceAction(action: String) {
        val svcIntent = Intent(this, AlarmRingService::class.java).apply {
            this.action = action
            intent.extras?.let { putExtras(it) }
        }
        startService(svcIntent)
        finish()
    }
}

@Composable
private fun AlarmRingScreen(onDismiss: () -> Unit, onSnooze: () -> Unit) {
    var timeText by remember { mutableStateOf(nowHHMM()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            timeText = nowHHMM()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(100.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeText,
                color = Color.White,
                fontSize = 80.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Wake up",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 16.sp,
                letterSpacing = 3.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 72.dp)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
            ) {
                Text("Dismiss", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.7f))
            ) {
                Text("Snooze 10 min", fontSize = 15.sp)
            }
        }
    }
}

private fun nowHHMM(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
