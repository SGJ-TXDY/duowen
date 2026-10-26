package cn.embeddedquiz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: QuizViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A restored activity still carries its launch intent; the review it asked for was already handled.
        if (savedInstanceState == null) openNotification(intent)
        setContent { QuizApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNotification(intent)
    }

    /** The ViewModel orders the review against exam restoration; the UI only forwards the tap once. */
    private fun openNotification(intent: Intent?) {
        if (intent?.getBooleanExtra(ReviewReminders.OPEN_REVIEW, false) == true) {
            intent.removeExtra(ReviewReminders.OPEN_REVIEW)
            vm.requestNotificationReview()
        }
    }
}
