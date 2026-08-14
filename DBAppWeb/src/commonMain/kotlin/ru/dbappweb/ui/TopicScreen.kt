package ru.dbappweb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.dbappweb.model.DemoExample
import ru.dbappweb.model.DemoTopic

/** Тематический экран адаптирует две панели к ширине окна, сохраняя обязательные кнопку назад и лог. */
@Composable
internal fun TopicScreen(
    topic: DemoTopic,
    logText: String,
    statusText: String,
    isBusy: Boolean,
    canRun: Boolean,
    onBack: () -> Unit,
    onClearLog: () -> Unit,
    onExampleSelected: (DemoExample) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack, enabled = !isBusy) { Text("<- Назад") }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(topic.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    topic.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 900.dp
            if (compact) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExampleList(
                        examples = topic.examples,
                        enabled = !isBusy && canRun,
                        modifier = Modifier.fillMaxWidth().weight(0.44f),
                        onExampleSelected = onExampleSelected,
                    )
                    LogPanel(
                        logText = logText,
                        statusText = statusText,
                        onClear = onClearLog,
                        modifier = Modifier.fillMaxWidth().weight(0.56f),
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExampleList(
                        examples = topic.examples,
                        enabled = !isBusy && canRun,
                        modifier = Modifier.fillMaxHeight().weight(0.38f),
                        onExampleSelected = onExampleSelected,
                    )
                    LogPanel(
                        logText = logText,
                        statusText = statusText,
                        onClear = onClearLog,
                        modifier = Modifier.fillMaxHeight().weight(0.62f),
                    )
                }
            }
        }
    }
}
