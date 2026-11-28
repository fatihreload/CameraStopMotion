package com.motionstudio.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.motionstudio.pro.editor.EditorScreen
import com.motionstudio.pro.export.ExportScreen
import com.motionstudio.pro.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = java.io.File(filesDir, "crash.txt")
        if (crash.exists()) { showCrash(crash); return }
        enableEdgeToEdge()
        setContent { MotionTheme { AppNav() } }
    }
}

private fun ComponentActivity.showCrash(file: java.io.File) {
    val pad = (16 * resources.displayMetrics.density).toInt()
    val title = android.widget.TextView(this).apply {
        text = "Aplikasi crash sebelumnya. Salin teks di bawah dan kirim ke Claude:"
        setTextColor(android.graphics.Color.WHITE); setPadding(pad, pad * 2, pad, pad)
    }
    val body = android.widget.TextView(this).apply {
        text = file.readText().take(6000); setTextIsSelectable(true)
        setTextColor(android.graphics.Color.parseColor("#22D3EE")); textSize = 11f; setPadding(pad, 0, pad, pad)
    }
    val btn = android.widget.Button(this).apply {
        text = "Hapus log & coba buka lagi"
        setOnClickListener { file.delete(); recreate() }
    }
    val col = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(title); addView(btn); addView(body)
    }
    setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(col) })
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val open = { id: String -> nav.navigate("editor/$id") }
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(onOpen = open, onLibrary = { nav.navigate("library") },
                onAssets = { nav.navigate("assets") }, onSettings = { nav.navigate("settings") })
        }
        composable("library") { LibraryScreen(onBack = { nav.popBackStack() }, onOpen = open) }
        composable("editor/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
            EditorScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() },
                onExport = { id -> nav.navigate("export/$id") })
        }
        composable("export/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
            ExportScreen(it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("assets") { AssetLibraryScreen(onBack = { nav.popBackStack() }) }
    }
}
