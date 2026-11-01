package com.aaryo.mobilestudio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Cloud / release control center. Cloud execution intentionally requires a trusted backend;
 * a GitHub PAT must never be embedded in the Android APK.
 */
@Composable
fun CloudProductionScreen(projectDir: File?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var backendUrl by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var status by remember { mutableStateOf("Cloud build is not connected") }
    var showSecurity by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    val projectName = projectDir?.name ?: "No project selected"

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DsTopBar(title = "Cloud & Release", leadingIcon = Icons.Default.ArrowBack, onLeading = onBack, showBell = false)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Hybrid workflow · Local editing · Trusted cloud builds", color = Ds.Muted, fontSize = 13.sp)

            DsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudQueue, null, tint = Ds.PurpleSoft)
                    Spacer(Modifier.width(8.dp))
                    Text("Cloud build connection", color = Ds.OnBg, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Backend required", color = Ds.Orange, fontSize = 11.sp)
                }
                OutlinedTextField(backendUrl, { backendUrl = it }, label = { Text("Trusted build API URL") }, placeholder = { Text("https://your-domain.example/api") }, singleLine = true, colors = dsFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repository, { repository = it }, label = { Text("GitHub repository (owner/repo)") }, singleLine = true, colors = dsFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, singleLine = true, colors = dsFieldColors(), modifier = Modifier.fillMaxWidth())
                DsButton("Validate configuration", Icons.Default.Link, onClick = {
                    status = when {
                        projectDir == null -> "Open a project before configuring a cloud build."
                        !backendUrl.startsWith("https://") -> "Use an HTTPS backend URL. Cloud builds are not sent directly from this app."
                        !repository.contains("/") -> "Enter repository as owner/repo."
                        else -> "Configuration validated locally. Connect a backend endpoint and authentication before dispatching builds."
                    }
                })
                Text(status, color = Ds.Muted, fontSize = 12.sp)
                Text("Selected project: $projectName", color = Ds.Muted, fontSize = 12.sp)
            }

            DsCard {
                Text("Release manager", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                DsTabs(listOf("Build", "History", "Checklist"), selected, { selected = it })
                when (selected) {
                    0 -> {
                        listOf("Debug APK · local development", "Release APK · signed distribution", "Android App Bundle · Play Console").forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Inventory2, null, tint = Ds.PurpleSoft); Spacer(Modifier.width(9.dp))
                                Text(item, color = Ds.OnBg, fontSize = 13.sp)
                            }
                        }
                        Text("Use the Build tab for local builds. Cloud dispatch becomes available after a trusted backend is configured.", color = Ds.Muted, fontSize = 12.sp)
                    }
                    1 -> Text("No cloud build records yet. Build history will appear here after backend integration.", color = Ds.Muted, fontSize = 13.sp)
                    else -> listOf(
                        "Application ID and version reviewed", "Target SDK and permissions reviewed", "Release build minified and tested",
                        "Upload key stored outside source control", "APK/AAB signature verified", "Privacy policy and store listing reviewed"
                    ).forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircleOutline, null, tint = Ds.PurpleSoft); Spacer(Modifier.width(8.dp))
                            Text(item, color = Ds.OnBg, fontSize = 13.sp)
                        }
                    }
                }
            }

            DsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = Ds.PurpleSoft); Spacer(Modifier.width(8.dp))
                    Text("Signing & security vault", color = Ds.OnBg, fontWeight = FontWeight.Bold)
                }
                Text("Signing secrets must remain in Android Keystore-backed storage or a CI secret store. Never commit keystores, passwords, or GitHub tokens to the repository or APK.", color = Ds.Muted, fontSize = 12.sp)
                DsButton(if (showSecurity) "Hide security guidance" else "Show security guidance", null, onClick = { showSecurity = !showSecurity }, primary = false)
                if (showSecurity) {
                    Text("Recommended: create an upload key outside the app; store KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD as GitHub Actions secrets; decode the keystore only inside the CI runner; delete temporary files after signing. For cloud dispatch, use a server-side GitHub App or short-lived installation token.", color = Ds.OnBg, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
