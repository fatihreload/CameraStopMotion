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
        enableEdgeToEdge()
        setContent { MotionTheme { AppNav() } }
    }
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
