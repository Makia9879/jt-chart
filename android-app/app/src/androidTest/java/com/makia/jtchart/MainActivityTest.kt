package com.makia.jtchart

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsDrawerExposesAllBPlanTabsAndCancelAction() {
        composeRule.onNodeWithTag("open-settings").performClick()

        composeRule.onNodeWithTag("settings-tab-market").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-tab-indicator").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("settings-tab-refresh").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("settings-cancel").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("open-settings").assertIsDisplayed()
    }
}
