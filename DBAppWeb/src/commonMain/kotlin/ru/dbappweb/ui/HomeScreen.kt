package ru.dbappweb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.dbappweb.model.ConnectionInfo
import ru.dbappweb.model.DemoCatalog
import ru.dbappweb.model.DemoTopic

/** Стартовый экран показывает состояние Docker API и шесть обязательных тематических кнопок. */
@Composable
internal fun HomeScreen(
    connectionInfo: ConnectionInfo?,
    statusText: String,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    onTopicSelected: (DemoTopic) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "DBAppWeb · лаборатория PostgreSQL 18",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Compose Multiplatform Web/Desktop -> Spring Boot 4.1.0 -> реальные транзакции и планы EXPLAIN.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BackendStatusCard(
            connectionInfo = connectionInfo,
            statusText = statusText,
            isBusy = isBusy,
            onRefresh = onRefresh,
        )

        Text(
            text = "Темы конспекта",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(DemoCatalog.topics, key = { it.id }) { topic ->
                // Разделы можно изучать и без соединения; блокируются только кнопки запуска SQL.
                TopicCard(topic = topic, enabled = !isBusy, onClick = { onTopicSelected(topic) })
            }
        }
    }
}

/** Компактная карточка делает видимыми все три звена: клиент, API и PostgreSQL. */
@Composable
private fun BackendStatusCard(
    connectionInfo: ConnectionInfo?,
    statusText: String,
    isBusy: Boolean,
    onRefresh: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Локальный бэкенд", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                connectionInfo?.let { info ->
                    Text(
                        "${info.serverVersion} · база ${info.database} · роль ${info.user}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Button(onClick = onRefresh, enabled = !isBusy) {
                Text(if (isBusy) "Проверяем..." else "Проверить")
            }
        }
    }
}
