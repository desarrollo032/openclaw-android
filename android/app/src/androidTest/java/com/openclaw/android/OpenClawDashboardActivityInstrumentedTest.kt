package com.openclaw.android

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class OpenClawDashboardActivityInstrumentedTest {

    private lateinit var scenario: ActivityScenario<OpenClawDashboardActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(OpenClawDashboardActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun testDashboardActivityLaunches() {
        scenario.onActivity { activity ->
            assert(activity != null)
        }
    }

    @Test
    fun testWebViewIsDisplayedInDashboard() {
        onView(withId(R.id.webView)).check(matches(isDisplayed()))
    }
}
