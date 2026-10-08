package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.R
import com.dilinkauto.server.ui.theme.CardElevatedColor
import com.dilinkauto.server.ui.theme.ChipUnselectedColor
import com.dilinkauto.server.ui.theme.WarningColor

/**
 * Settings row + WiFi-ADB setup card from the car launch screen.
 *
 * Extracted from CarLaunchScreen (docs/audit-srp-dry.md SRP-9): these are a
 * different subject from the connection status card they were embedded in.
 * Note they were not standalone top-level composables — SettingSection was
 * invoked three times from inside ConnectionStatusCard, so moving them also
 * meant moving those call sites out.
 */
@Composable
internal fun SettingSection(
    title: String,
    subtitle: String,
    hint: String,
    currentValueText: String,
    isCustom: Boolean,
    presets: List<Pair<String, Int>>,
    selectedPresetValue: Int,
    onPresetSelected: (Int) -> Unit,
    onManualValueChange: (String) -> Unit,
    manualSuffix: String = ""
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        currentValueText,
                        color = WarningColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Manual Input field
            OutlinedTextField(
                value = if (isCustom) currentValueText.replace(Regex("[^0-9]"), "") else "",
                onValueChange = onManualValueChange,
                singleLine = true,
                placeholder = {
                    Text(
                        if (manualSuffix.isNotEmpty()) manualSuffix else "自定义",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.width(96.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        Spacer(Modifier.height(8.dp))

        // Preset Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { (label, value) ->
                val isSelected = selectedPresetValue == value
                Button(
                    onClick = { onPresetSelected(value) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else ChipUnselectedColor,
                        contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
internal fun WifiAdbSetupCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardElevatedColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.wifi_adb_setup_title),
                color = WarningColor,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.wifi_adb_setup_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 20.sp
            )
        }
    }
}
