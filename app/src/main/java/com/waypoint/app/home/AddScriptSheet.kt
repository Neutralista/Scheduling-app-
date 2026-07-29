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
function MyScript() {
  Script.call(this, {
    id: 'my_script',
    displayName: 'My Script'
  });
}
MyScript.prototype = Object.create(Script.prototype);

// Return a view-spec to render a widget card.
// Remove this to make a settings-only script.
MyScript.prototype.widget = function(state, signals) {
  return {
    title:       this.displayName,
    done:        state.doneToday,
    actionLabel: state.doneToday ? 'Undo' : 'Mark done'
  };
};

// Called when the action button is pressed.
MyScript.prototype.onAction = function(state, signals, scripts) {
  return Object.assign({}, state, { doneToday: !state.doneToday });
};

// Called periodically. Return new state or null to keep current.
MyScript.prototype.onTick = function(state, signals, scripts) {
  return null;
};

new MyScript()
""".trimIndent()

@Composable
fun AddScriptSheet(
    onDismiss: () -> Unit,
    onLoad: (source: String) -> String?,
    initialSource: String = TEMPLATE,
    title: String = "Add script",
    subtitle: String = "JavaScript ending with new YourScript()"
) {
    var code by remember { mutableStateOf(initialSource) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .imePadding()
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        BasicTextField(
            value = code,
            onValueChange = { code = it; error = null },
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
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
