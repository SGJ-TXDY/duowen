package cn.embeddedquiz

enum class Page(val label: String) {
    HOME("首页"),
    QUESTION("刷题"),
    BROWSE("分类"),
    REVIEW("智能复习"),
    PROGRAMMING("编程专区"),
    MISTAKES("错题收藏"),
    EXAM("模拟考试"),
    STATISTICS("历史统计"),
    NOTES("便签"),
    SETTINGS("设置"),
    MORE("更多"),
    WORKBENCH("待核对工作台"),
    COSTS("AI 费用估算"),
    ERROR_LOG("本地错误记录"),
}

enum class AiRequestType(val label: String) {
    EXPLANATION("生成详细解析"),
    FOLLOW_UP("追问"),
    SUGGESTED_ANSWER("AI 建议答案（待核对）"),
    VARIANT("出一道同知识点变式题"),
    CODE_REVIEW("AI 代码审查"),
}
