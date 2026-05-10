package com.openclaw.android

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityInstrumentedTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun testActivityLaunches() {
        // Verificar que la actividad se lanza sin crash
        scenario.onActivity { activity ->
            assert(activity != null)
        }
    }

    @Test
    fun testWebViewIsPresent() {
        // Verificar que el WebView está presente
        onView(withId(R.id.webView))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testWebViewIsEnabled() {
        // Verificar que el WebView está habilitado
        onView(withId(R.id.webView))
            .check(matches(isEnabled()))
    }
}
