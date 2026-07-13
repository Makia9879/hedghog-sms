package com.makia.hedgehogsms.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationCoreTest {
    @Test fun `otp requires both a code and verification meaning`() {
        assertTrue(OtpDetector.isLikelyOtp("【虚构甲】验证码 A7K9，仅用于本次测试"))
        assertTrue(OtpDetector.isLikelyOtp("【虚构甲】验证码123456，仅用于本次测试"))
        assertFalse(OtpDetector.isLikelyOtp("虚构订单号 123456 已发货"))
        assertFalse(OtpDetector.isLikelyOtp("请输入验证码完成虚构操作"))
        assertFalse(OtpDetector.isLikelyOtp("虚构金额 123456 元"))
    }

    @Test fun `explicit body signature creates platform while unsigned otp stays pending`() {
        val signed = com.makia.hedgehogsms.data.SmsRecord(1, 1, 1, null, "【虚构平台】验证码123456", null)
        val unsigned = signed.copy(id = 2, body = "您的验证码654321")

        assertEquals(MessageClassStatus.LABELED.name, PlatformRuleClassifier.classify(signed, 1).status)
        assertEquals("虚构平台", PlatformRuleClassifier.classify(signed, 1).platformDisplayName)
        assertEquals(MessageClassStatus.PENDING_LABEL.name, PlatformRuleClassifier.classify(unsigned, 1).status)
    }

    @Test fun `feature extraction redacts sensitive values and is keyed`() {
        val first = TextFeatureExtractor(ByteArray(32) { 1 })
            .extract("【虚构甲】验证码 483921，日期 2026-01-02，访问 https://invalid.example/x?t=secret")
        val sameShape = TextFeatureExtractor(ByteArray(32) { 1 })
            .extract("【虚构甲】验证码 739105，日期 2027-03-04，访问 https://invalid.example/x?t=other")
        val anotherKey = TextFeatureExtractor(ByteArray(32) { 2 })
            .extract("【虚构甲】验证码 483921，日期 2026-01-02，访问 https://invalid.example/x?t=secret")

        assertEquals(first.buckets, sameShape.buckets)
        assertNotEquals(first.buckets, anotherKey.buckets)
        assertTrue(first.buckets.values.all { it in 1..3 })
        assertTrue(first.buckets.keys.all { it in 0 until 16_384 })
    }

    @Test fun `online model learns predicts and corrects exact sample statistics`() {
        val model = OnlineMultinomialNaiveBayes()
        val alpha = SparseFeatures(mapOf(10 to 2, 11 to 1))
        val beta = SparseFeatures(mapOf(20 to 2, 21 to 1))
        model.learn(1, 100, alpha)
        model.learn(2, 100, alpha)
        model.learn(3, 200, beta)
        model.learn(4, 200, beta)

        val before = model.predict(alpha, ConfidenceMode.BALANCED)
        assertEquals(100L, before.best?.labelId)
        assertTrue(before.autoAccepted)

        model.correct(1, 100, 200)
        assertEquals(1, model.snapshot().classes.getValue(100).documentCount)
        assertEquals(3, model.snapshot().classes.getValue(200).documentCount)
        model.correct(2, 100, 200)
        assertFalse(model.predict(alpha, ConfidenceMode.BALANCED).autoAccepted)
        assertTrue(model.snapshot().classes.values.all { it.documentCount >= 0 && it.featureCount >= 0 })
    }

    @Test fun `different senders remain different keyed weak features`() {
        val extractor = TextFeatureExtractor(ByteArray(32) { 3 })
        val first = extractor.extract("【虚构甲】验证码 1234", sender = "10000000001")
        val second = extractor.extract("【虚构甲】验证码 1234", sender = "10000000002")

        assertNotEquals(first.buckets, second.buckets)
    }

    @Test fun `one class or too few confirmed samples never auto accepts`() {
        val model = OnlineMultinomialNaiveBayes()
        val features = SparseFeatures(mapOf(7 to 1))
        model.learn(1, 1, features)
        model.learn(2, 1, features)
        assertFalse(model.predict(features, ConfidenceMode.AUTOMATIC).autoAccepted)
        model.learn(3, 2, SparseFeatures(mapOf(8 to 1)))
        assertFalse(model.predict(features, ConfidenceMode.CONSERVATIVE).autoAccepted)
    }
}
