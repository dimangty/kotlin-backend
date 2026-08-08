package ru.dbapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.dbapp.model.ConnectionInfo
import ru.dbapp.model.DemoCatalog
import ru.dbapp.model.DemoTopic

/** Стартовый экран показывает подключение и шесть обязательных тематических кнопок. */
@Composable
internal fun HomeScreen(
    url: String,
    user: String,
    password: String,
    onUrlChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    connectionInfo: ConnectionInfo?,
    statusText: String,
    isBusy: Boolean,
    onConnect: () -> Unit,
    onTopicSelected: (DemoTopic) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "DBApp · лаборатория PostgreSQL 18",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Живые примеры из конспекта: две JDBC-сессии, реальные блокировки и планы EXPLAIN.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionCard(
            url = url,
            user = user,
            password = password,
            onUrlChange = onUrlChange,
            onUserChange = onUserChange,
            onPasswordChange = onPasswordChange,
            connectionInfo = connectionInfo,
            statusText = statusText,
            isBusy = isBusy,
            onConnect = onConnect,
        )

        Text(
            text = "Темы",
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
                TopicCard(topic = topic, enabled = !isBusy, onClick = { onTopicSelected(topic) })
            }
        }
    }
}
