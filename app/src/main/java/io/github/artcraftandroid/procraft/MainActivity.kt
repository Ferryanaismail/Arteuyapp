package io.github.artcraftandroid.procraft

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.ui.AppRoot
import io.github.artcraftandroid.procraft.ui.ProCraftTheme

class MainActivity : ComponentActivity() {
    private var viewModelRef: CanvasViewModel? = null
    private var pendingImportUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingImportUri = extractSharedImageUri(intent)
        setContent {
            val vm: CanvasViewModel = viewModel()
            viewModelRef = vm
            val pending = pendingImportUri
            if (pending != null) {
                pendingImportUri = null
                vm.importPictureAsArtwork(pending)
            }
            ProCraftTheme(light = vm.gestureSettings.lightInterface) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedImageUri(intent)?.let { uri ->
            val vm = viewModelRef
            if (vm != null) {
                vm.importPictureAsArtwork(uri)
            } else {
                pendingImportUri = uri
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharedImageUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
