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
}

enum class AiRequestType {
    EXPLANATION,
    FOLLOW_UP,
}
