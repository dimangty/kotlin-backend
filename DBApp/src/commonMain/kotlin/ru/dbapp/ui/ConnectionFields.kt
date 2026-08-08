package ru.dbapp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation

/** Вертикальная версия полей используется, когда окно стало узким. */
@Composable
internal fun ConnectionFields(
    url: String,
    user: String,
    password: String,
    onUrlChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("JDBC URL") },
        singleLine = true,
    )
    OutlinedTextField(
        value = user,
        onValueChange = onUserChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Пользователь") },
        singleLine = true,
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Пароль") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}
