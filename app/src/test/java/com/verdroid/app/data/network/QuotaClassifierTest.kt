package com.verdroid.app.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaClassifierTest {
    @Test fun paidModelNeverDailyFree() {
        assertFalse(QuotaClassifier.isDailyFreeQuota("deepseek/deepseek-v4.1-flash", "Rate limit exceeded: free-models-per-day"))
        assertFalse(QuotaClassifier.isDailyFreeQuota("deepseek/deepseek-v4.1-flash", "requests per day exceeded"))
    }
    @Test fun freeModelExplicitDailyError() {
        assertTrue(QuotaClassifier.isDailyFreeQuota("deepseek/deepseek-chat:free", "Rate limit exceeded: free-models-per-day. Add 10 credits"))
    }
    @Test fun freeModelPlain429IsNotDaily() {
        assertFalse(QuotaClassifier.isDailyFreeQuota("x/y:free", "Rate limit exceeded, retry shortly"))
        assertFalse(QuotaClassifier.isDailyFreeQuota("x/y:free", "Insufficient credits"))
        assertFalse(QuotaClassifier.isDailyFreeQuota("x/y:free", null))
    }
    @Test fun allDailyRequiresOnlyFreeModels() {
        assertFalse(QuotaClassifier.allDailyFree(listOf("deepseek/deepseek-v4.1-flash"), 0, 1))
        assertFalse(QuotaClassifier.allDailyFree(listOf("a:free", "deepseek/deepseek-v4.1-flash"), 1, 0))
        assertFalse(QuotaClassifier.allDailyFree(listOf("a:free"), 1, 1))
        assertTrue(QuotaClassifier.allDailyFree(listOf("a:free", "b:free"), 2, 0))
    }
}
