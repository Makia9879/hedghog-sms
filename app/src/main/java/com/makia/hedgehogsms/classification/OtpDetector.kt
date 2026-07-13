package com.makia.hedgehogsms.classification

object OtpDetector {
    private val meaning = Regex("验证码|校验码|动态码|一次性密码|\\botp\\b|verification\\s*code", RegexOption.IGNORE_CASE)
    private val code = Regex("(?<![A-Za-z0-9])(?=[A-Za-z0-9]{4,8}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]+")

    fun isLikelyOtp(text: CharSequence): Boolean = meaning.containsMatchIn(text) && code.containsMatchIn(text)
}
