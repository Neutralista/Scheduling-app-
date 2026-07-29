package com.waypoint.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TEMPLATE = """
function MyHabit() {
  HabitWidget.call(this, {
    id: 'my_habit',
    displayName: 'My Habit'
  });
}
MyHabit.prototype = Object.create(HabitWidget.prototype);

MyHabit.prototype.render = function(state) {
  return {
    title: this.displayName,
    done: state.doneToday,
    actionLabel: state.doneToday ? 'Undo' : 'Mark done'
  };
};

MyHabit.prototype.onAction = function(state) {
  return Object.assign({}, state, { doneToday: !state.doneToday });
};

new MyHabit()
""".trimIndent()

/**
 * Bottom-sheet body for pasting and loading a scripted widget.
 *
 * [onLoad] receives the JS source; returns null on success or an error string.
 */
@Composable
fun AddWidgetSheet(
    onDismiss: () -> Unit,
    onLoad: (source: String) -> String?
) {
    var code by remember { mutableStateOf(TEMPLATE) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .imePadding()
    ) {
        Text(
            "Add widget",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "Paste JavaScript that ends with new YourClass()",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        // Code editor
        BasicTextField(
            value = code,
            onValueChange = { code = it; error = null },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    val err = onLoad(code)
                    if (err != null) error = err else onDismiss()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Load")
            }
        }
    }
}
