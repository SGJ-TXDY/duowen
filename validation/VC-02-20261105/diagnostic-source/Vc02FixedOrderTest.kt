package cn.embeddedquiz

import org.junit.runner.Description
import org.junit.runner.Request
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.notification.RunNotifier

/** Diagnostic only: nested original tests, explicit order independent of Gradle failure history. */
@RunWith(Vc02FixedOrderRunner::class)
class Vc02FixedOrderTest

class Vc02FixedOrderRunner(private val suite: Class<*>) : Runner() {
    private data class Step(val type: Class<*>, val method: String? = null)
    private val mode = System.getenv("VC02_MODE").orEmpty()
    private val steps = buildList {
        if (mode.contains("accent")) {
            add(Step(AccentPreferenceTest::class.java, "defaultsToFirstAccentAndSurvivesARestart"))
            add(Step(AccentPreferenceTest::class.java, "staleStoredValueFallsBackAndUnknownChoiceIsRejected"))
        }
        if (mode.contains("allai")) {
            add(Step(AiConversationUiTest::class.java, "slowRequestOnAStaysOnAAndItsFailureNeverReachesB"))
            add(Step(AiConversationUiTest::class.java, "changedQuestionVersionHidesTheOldVersionRequestUntilYouReturn"))
            add(Step(AiConversationUiTest::class.java, "costLineUsesReadableTextColorInLightAndDark"))
        }
        add(Step(AiConversationUiTest::class.java, "cacheHitAndSuccessLeaveNoPendingOrDuplicateBubble"))
        if (mode.contains("draftprefix") || mode.contains("boundaryprefix")) {
            add(Step(CodeDraftBoundaryTest::class.java))
        }
        if (mode.contains("draftprefix") || mode.contains("persistprefix")) {
            add(Step(CodeDraftPersistenceTest::class.java))
        }
        if (mode.contains("oneboundary")) add(Step(CodeDraftBoundaryTest::class.java, "clearingOwnerFlushesTheLastWindowBeforeClosingTheDatabase"))
        add(Step(CodeDraftSaveUiTest::class.java, "onlyTextChangesAreReportedAndTheCaretSurvivesTheRoundTrip"))
        if (mode.contains("four")) {
            add(Step(CodeDraftSaveUiTest::class.java, "indentInsertsAtTheCaretAsOneChange"))
            add(Step(CodeDraftSaveUiTest::class.java, "disabledEditorKeepsTheTextReadableButAcceptsNoEdit"))
            add(Step(CodeDraftSaveUiTest::class.java, "theExclusiveBoundaryFreezesCodeAndNotesUntilItEnds"))
        }
    }
    override fun getDescription(): Description = Description.createSuiteDescription(suite).apply {
        steps.forEach { addChild(if (it.method == null) Description.createSuiteDescription(it.type)
            else Description.createTestDescription(it.type, it.method)) }
    }
    override fun run(notifier: RunNotifier) {
        steps.forEachIndexed { i, step ->
            println("VC02 ORDER ${i+1}/${steps.size} START ${step.type.simpleName}.${step.method ?: "<class>"} mode=$mode")
            val request = if (step.method == null) Request.aClass(step.type) else Request.method(step.type, step.method)
            request.runner.run(notifier)
            println("VC02 ORDER ${i+1}/${steps.size} END ${step.type.simpleName}.${step.method ?: "<class>"}")
        }
    }
}