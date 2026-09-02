package com.lomivox.appcloner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class CloneConfig(
    val cloneId: String,
    val appName: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val output: String,
    val buildType: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppClonerScreen() }
    }
}

@Composable
fun AppClonerScreen() {
    var appName by remember { mutableStateOf("") }
    var applicationId by remember { mutableStateOf("") }
    var versionName by remember { mutableStateOf("1.0.0") }
    var versionCode by remember { mutableStateOf("1") }
    var output by remember { mutableStateOf("apk") }
    var message by remember { mutableStateOf("") }

    val packageValid = Regex(
        "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
    ).matches(applicationId)

    val valid = appName.isNotBlank() &&
        packageValid &&
        versionName.isNotBlank() &&
        versionCode.toIntOrNull()?.let { it > 0 } == true

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("APP CLONER", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text("Create an authorized Android clone configuration.")

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = appName,
            onValueChange = { appName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Clone App Name") },
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = applicationId,
            onValueChange = { applicationId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Application ID / Package") },
            supportingText = {
                if (applicationId.isNotEmpty() && !packageValid)
                    Text("Example: com.example.myclone")
            },
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = versionName,
                onValueChange = { versionName = it },
                modifier = Modifier.weight(1f),
                label = { Text("Version") },
                singleLine = true
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = versionCode,
                onValueChange = { versionCode = it.filter(Char::isDigit) },
                modifier = Modifier.weight(1f),
                label = { Text("Code") },
                singleLine = true
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Output")

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(output == "apk", { output = "apk" })
            Text("APK")
            Spacer(Modifier.width(16.dp))
            RadioButton(output == "aab", { output = "aab" })
            Text("AAB")
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val id = appName.trim().lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                val config = CloneConfig(
                    cloneId = id,
                    appName = appName.trim(),
                    applicationId = applicationId.trim(),
                    versionName = versionName.trim(),
                    versionCode = versionCode.toInt(),
                    output = output,
                    buildType = "release"
                )
                message = "Configuration ready\n" +
                    "Name: ${config.appName}\n" +
                    "Package: ${config.applicationId}\n" +
                    "Version: ${config.versionName} (${config.versionCode})\n" +
                    "Output: ${config.output.uppercase()}\n\n" +
                    "Run GitHub Actions → Build App Clone with these values."
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("GENERATE CLONE CONFIG")
        }

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(message, Modifier.padding(16.dp))
            }
        }
    }
}
