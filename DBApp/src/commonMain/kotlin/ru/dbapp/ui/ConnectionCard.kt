package ru.dbapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.dbapp.model.ConnectionInfo

/** Карточка подключения оставляет параметры видимыми и не скрывает причину ошибки. */
@Composable
internal fun ConnectionCard(
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
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (connectionInfo != null) Color(0xFF1B8F58) else MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
                if (isBusy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compact = maxWidth < 850.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConnectionFields(url, user, password, onUrlChange, onUserChange, onPasswordChange)
                        Button(onClick = onConnect, enabled = !isBusy) { Text("Подключиться и подготовить схему") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = onUrlChange,
                            modifier = Modifier.weight(1.6f),
                            label = { Text("JDBC URL") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = user,
                            onValueChange = onUserChange,
                            modifier = Modifier.weight(0.7f),
                            label = { Text("Пользователь") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            modifier = Modifier.weight(0.7f),
                            label = { Text("Пароль") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Button(onClick = onConnect, enabled = !isBusy, modifier = Modifier.height(56.dp)) {
                            Text("Подключиться")
                        }
                    }
                }
            }

            connectionInfo?.let { info ->
                Text(
                    text = "${info.serverVersion} · БД ${info.database} · роль ${info.user}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
