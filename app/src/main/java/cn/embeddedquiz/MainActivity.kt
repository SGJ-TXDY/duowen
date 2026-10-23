package cn.embeddedquiz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {
    private val vm: QuizViewModel by viewModels()
    private var reviewAfterLoad by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openNotification(intent)
        setContent {
            LaunchedEffect(vm.ready, reviewAfterLoad) {
                if (vm.ready && reviewAfterLoad) {
                    vm.page = Page.REVIEW
                    reviewAfterLoad = false
                }
            }
            QuizApp(vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNotification(intent)
    }

    private fun openNotification(intent: Intent?) {
        if (intent?.getBooleanExtra(ReviewReminders.OPEN_REVIEW, false) == true) {
            reviewAfterLoad = true
            vm.page = Page.REVIEW
            intent.removeExtra(ReviewReminders.OPEN_REVIEW)
        }
    }
}
