package io.github.sebbejohansson.yggdrasil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebbejohansson.yggdrasil.norn.ChatScreen
import io.github.sebbejohansson.yggdrasil.norn.ChatViewModel
import io.github.sebbejohansson.yggdrasil.ui.YggTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as YggApp).container
        setContent {
            YggTheme {
                val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
                ChatScreen(vm)
            }
        }
    }
}
