package cn.embeddedquiz

import androidx.compose.runtime.Composable

@Composable
fun ExamScreen(vm: QuizViewModel) {
    Browse(vm, "模拟考试")
}
