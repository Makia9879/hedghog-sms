package com.makia.hedgehogsms.classification

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.makia.hedgehogsms.data.SmsRecord
import com.makia.hedgehogsms.AppContainer
import java.security.MessageDigest
import java.util.Locale

enum class MessageClassStatus { NON_OTP, PENDING_LABEL, LABELED }
enum class MessageClassSource { RULE, LOCAL_MODEL, HUMAN, LLM }

@Entity(tableName = "message_classification")
data class MessageClassification(
    @PrimaryKey val sourceMessageId: Long,
    val isOtp: Boolean,
    val platformKey: String?,
    val platformDisplayName: String?,
    val status: String,
    val source: String?,
    val isHumanConfirmed: Boolean = false,
    val updatedAt: Long,
)

data class PlatformSummary(
    val platformKey: String,
    val displayName: String,
    val otpCount: Int,
    val latestMessageDate: Long,
    val slot1Count: Int,
    val slot2Count: Int,
    val unknownCount: Int,
)

data class PlatformIdentity(val platformKey: String, val displayName: String)

class PlatformClassificationService(private val container: AppContainer) {
    suspend fun classify(sms: SmsRecord, now: Long, mode: ConfidenceMode = ConfidenceMode.BALANCED): MessageClassification {
        val rule = PlatformRuleClassifier.classify(sms, now)
        if (rule.status != MessageClassStatus.PENDING_LABEL.name) return rule
        val prediction = container.trainingRepository.predict(container.featureExtractor.extract(sms.body, sms.sender), mode)
        if (!prediction.autoAccepted) return rule
        val best = prediction.best ?: return rule
        val identity = container.database.classificationDao().knownPlatforms()
            .firstOrNull { PlatformRuleClassifier.stablePlatformLabelId(it.displayName) == best.labelId }
            ?: return rule
        return rule.copy(
            platformKey = identity.platformKey,
            platformDisplayName = identity.displayName,
            status = MessageClassStatus.LABELED.name,
            source = MessageClassSource.LOCAL_MODEL.name,
        )
    }
}

object PlatformRuleClassifier {
    private val bracketedSignature = Regex("[【\\[]\\s*([^】\\]]{1,24})\\s*[】\\]]")
    private val rejectedSignature = Regex("验证码|校验码|动态码|通知|提醒|短信", RegexOption.IGNORE_CASE)

    fun classify(sms: SmsRecord, now: Long): MessageClassification {
        if (!OtpDetector.isLikelyOtp(sms.body)) {
            return MessageClassification(sms.id, false, null, null, MessageClassStatus.NON_OTP.name, null, updatedAt = now)
        }
        val displayName = bracketedSignature.find(sms.body)?.groupValues?.get(1)
            ?.trim()?.takeIf { it.isNotBlank() && !rejectedSignature.matches(it) }
        return if (displayName == null) {
            MessageClassification(sms.id, true, null, null, MessageClassStatus.PENDING_LABEL.name, null, updatedAt = now)
        } else {
            MessageClassification(
                sms.id,
                true,
                stablePlatformKey(displayName),
                displayName,
                MessageClassStatus.LABELED.name,
                MessageClassSource.RULE.name,
                updatedAt = now,
            )
        }
    }

    fun stablePlatformKey(name: String): String = MessageDigest.getInstance("SHA-256")
        .digest(name.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }

    fun stablePlatformLabelId(name: String): Long = MessageDigest.getInstance("SHA-256")
        .digest(name.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        .and(Long.MAX_VALUE)
}
