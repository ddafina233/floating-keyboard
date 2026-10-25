package com.example.floatingkeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SetupScreen(
                        modifier = Modifier.padding(padding),
                        onOpenImeSettings = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onPickIme = {
                            val imm = getSystemService(InputMethodManager::class.java)
                            imm?.showInputMethodPicker()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(
    modifier: Modifier = Modifier,
    onOpenImeSettings: () -> Unit,
    onPickIme: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("浮动键盘", fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium)
        Text(
            "这是一个真正的输入法（IME）：启用后，任何 App 的输入框里都能用它打字，" +
                "键盘悬浮在屏幕上，可以拖着走。",
            fontSize = 14.sp
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("第 1 步：启用键盘", fontSize = 15.sp)
                Button(onClick = onOpenImeSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("打开输入法设置")
                }
                Text("第 2 步：切换当前输入法", fontSize = 15.sp)
                Button(onClick = onPickIme, modifier = Modifier.fillMaxWidth()) {
                    Text("选择输入法")
                }
            }
        }

        HorizontalDivider()

        Text("第 3 步：在这里试打", fontSize = 15.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("点我弹出浮动键盘") },
            minLines = 3
        )
        Text("已输入 ${text.length} 个字符", fontSize = 13.sp)
    }
}
