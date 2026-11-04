package cn.embeddedquiz
import org.junit.runner.RunWith
import org.junit.runners.Suite
@RunWith(Suite::class)
@Suite.SuiteClasses(ReviewReminderTest::class, StartupTest::class)
class T3StartupOrderProbeSuite