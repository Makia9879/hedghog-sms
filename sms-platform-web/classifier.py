"""手机端平台区分的同一套规则，外加同样门槛的本地模型。

先判断是不是验证码。是的话，取正文里第一处【签名】或 [签名]。
签名本身如果只是「验证码 / 通知 / 提醒 / 短信」这类词，不当成平台。
没有可用签名就进入待确认。待确认的短信只有在本地模型越过
与手机相同的平衡门槛时，才会自动标上你曾经确认过的平台。
"""

from __future__ import annotations

import hashlib
import hmac
import math
import re
import unicodedata
from dataclasses import dataclass, field

MEANING = re.compile(r"验证码|校验码|动态码|一次性密码|\botp\b|verification\s*code", re.IGNORECASE)
CODE = re.compile(r"(?<![A-Za-z0-9])(?=[A-Za-z0-9]{4,8}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*\d)[A-Za-z0-9]+")
BRACKET = re.compile(r"[【\[]\s*([^】\]]{1,24})\s*[】\]]")
REJECTED_SIGNATURE = re.compile(r"验证码|校验码|动态码|通知|提醒|短信", re.IGNORECASE)
WHITESPACE = re.compile(r"\s+")
URL = re.compile(r"https?://[^\s]+", re.IGNORECASE)
DATE = re.compile(r"\b(?:19|20)\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?\b")
MONEY = re.compile(r"(?:[¥￥$]\s*\d+(?:[.,]\d+)?|\d+(?:[.,]\d+)?\s*(?:元|美元|usd|cny))", re.IGNORECASE)
PHONE = re.compile(r"(?<!\d)(?:\+?86[- ]?)?1\d{10}(?!\d)")
NUMBER = re.compile(r"\d{2,}")

NON_OTP = "NON_OTP"
PENDING = "PENDING_LABEL"
LABELED = "LABELED"
RULE = "RULE"
LOCAL_MODEL = "LOCAL_MODEL"
HUMAN = "HUMAN"

# 与手机 ConfidenceMode.BALANCED 相同。
BALANCED_POSTERIOR = 0.85
BALANCED_MARGIN = 0.30
BALANCED_MIN_SAMPLES = 2
BUCKETS = 16384


def is_likely_otp(text: str) -> bool:
    return MEANING.search(text) is not None and CODE.search(text) is not None


def display_name(raw: str) -> str:
    normalized = WHITESPACE.sub(" ", unicodedata.normalize("NFC", raw)).strip()
    if not normalized:
        raise ValueError("platform label is blank")
    return normalized


def comparison_key(raw: str) -> str:
    return display_name(raw).lower()


def stable_platform_key(name: str) -> str:
    return hashlib.sha256(comparison_key(name).encode("utf-8")).digest()[:12].hex()


def stable_platform_label_id(name: str) -> int:
    digest = hashlib.sha256(comparison_key(name).encode("utf-8")).digest()[:8]
    return int.from_bytes(digest, "big") & 0x7FFFFFFFFFFFFFFF


@dataclass
class Classification:
    is_otp: bool
    platform_key: str | None
    platform_name: str | None
    status: str
    source: str | None
    label_id: int | None = None


def rule_classify(body: str) -> Classification:
    if not is_likely_otp(body):
        return Classification(False, None, None, NON_OTP, None)
    match = BRACKET.search(body)
    name = None
    if match:
        try:
            candidate = display_name(match.group(1))
        except ValueError:
            candidate = ""
        if candidate and REJECTED_SIGNATURE.fullmatch(candidate) is None:
            name = candidate
    if name is None:
        return Classification(True, None, None, PENDING, None)
    return Classification(True, stable_platform_key(name), name, LABELED, RULE, stable_platform_label_id(name))


def _normalize(raw: str) -> str:
    return WHITESPACE.sub(" ", unicodedata.normalize("NFC", raw).lower()).strip()


def redact(raw: str) -> str:
    value = _normalize(raw)
    value = URL.sub(lambda match: match.group(0).split("?", 1)[0] + "?<url_param>", value)
    value = DATE.sub("<date>", value)
    value = MONEY.sub("<amount>", value)
    value = PHONE.sub("<phone>", value)
    value = CODE.sub("<code>", value)
    value = NUMBER.sub("<number>", value)
    return WHITESPACE.sub(" ", value).strip()


def _length_band(length: int) -> str:
    if length < 40:
        return "short"
    if length < 120:
        return "medium"
    return "long"


class FeatureExtractor:
    def __init__(self, secret: bytes):
        if not secret:
            raise ValueError("hmac key is empty")
        self.secret = secret

    def extract(self, text: str, sender: str | None = None) -> dict[int, int]:
        normalized = redact(text)
        tokens: list[str] = []
        chars = list(normalized)
        for size in (1, 2, 3):
            for start in range(0, len(chars) - size + 1):
                tokens.append("body:" + "".join(chars[start : start + size]))
        tokens.append("structure:length:" + _length_band(len(normalized)))
        if is_likely_otp(text):
            tokens.append("structure:otp")
        if sender and sender.strip():
            tokens.append("sender:" + _normalize(sender))
        counts: dict[int, int] = {}
        for token in tokens:
            bucket = self._bucket(token)
            counts[bucket] = min(3, counts.get(bucket, 0) + 1)
        return counts

    def _bucket(self, token: str) -> int:
        digest = hmac.new(self.secret, token.encode("utf-8"), hashlib.sha256).digest()
        first = int.from_bytes(digest[:4], "big")
        return first % BUCKETS


@dataclass
class _ClassStats:
    documents: int = 0
    total: int = 0
    buckets: dict[int, int] = field(default_factory=dict)


class LocalModel:
    def __init__(self) -> None:
        self.classes: dict[int, _ClassStats] = {}
        self.samples: dict[int, tuple[int, dict[int, int]]] = {}

    def learn(self, sample_id: int, label_id: int, features: dict[int, int]) -> None:
        if sample_id in self.samples:
            raise ValueError("sample already learned")
        self._add(label_id, features)
        self.samples[sample_id] = (label_id, dict(features))

    def correct(self, sample_id: int, old_label_id: int, new_label_id: int) -> None:
        label_id, features = self.samples[sample_id]
        if label_id != old_label_id:
            raise ValueError("sample label mismatch")
        self._subtract(old_label_id, features)
        self._add(new_label_id, features)
        self.samples[sample_id] = (new_label_id, features)

    def predict(self, features: dict[int, int]) -> tuple[int | None, bool]:
        active = {label: stats for label, stats in self.classes.items() if stats.documents > 0}
        if not active:
            return None, False
        documents = float(sum(stats.documents for stats in active.values()))
        alpha = 1.0
        logs: list[tuple[int, float, int]] = []
        for label, stats in active.items():
            score = math.log(stats.documents / documents)
            denominator = stats.total + alpha * BUCKETS
            for bucket, count in features.items():
                seen = stats.buckets.get(bucket, 0)
                score += count * math.log((seen + alpha) / denominator)
            logs.append((label, score, stats.documents))
        maximum = max(item[1] for item in logs)
        normalizer = sum(math.exp(item[1] - maximum) for item in logs)
        ranked = sorted(
            ((label, math.exp(score - maximum) / normalizer, samples) for label, score, samples in logs),
            key=lambda item: item[1],
            reverse=True,
        )
        best_label, best_posterior, best_samples = ranked[0]
        second = ranked[1][1] if len(ranked) > 1 else 0.0
        margin = best_posterior - second
        accepted = (
            len(ranked) >= 2
            and best_samples >= BALANCED_MIN_SAMPLES
            and best_posterior >= BALANCED_POSTERIOR
            and margin >= BALANCED_MARGIN
        )
        return best_label, accepted

    def _add(self, label_id: int, features: dict[int, int]) -> None:
        stats = self.classes.setdefault(label_id, _ClassStats())
        stats.documents += 1
        for bucket, count in features.items():
            stats.buckets[bucket] = stats.buckets.get(bucket, 0) + count
            stats.total += count

    def _subtract(self, label_id: int, features: dict[int, int]) -> None:
        stats = self.classes[label_id]
        stats.documents -= 1
        for bucket, count in features.items():
            remaining = stats.buckets.get(bucket, 0) - count
            if remaining < 0:
                raise ValueError("feature count would become negative")
            if remaining == 0:
                stats.buckets.pop(bucket, None)
            else:
                stats.buckets[bucket] = remaining
            stats.total -= count


def classify_text(body: str, sender: str | None, model: LocalModel, extractor: FeatureExtractor, names_by_label: dict[int, tuple[str, str]]) -> Classification:
    rule = rule_classify(body)
    if rule.status != PENDING:
        return rule
    label_id, accepted = model.predict(extractor.extract(body, sender))
    if not accepted or label_id is None:
        return rule
    known = names_by_label.get(label_id)
    if known is None:
        return rule
    platform_key, platform_name = known
    return Classification(True, platform_key, platform_name, LABELED, LOCAL_MODEL, label_id)
