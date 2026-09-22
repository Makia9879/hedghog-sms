import tempfile
import unittest
from pathlib import Path

from classifier import (
    FeatureExtractor,
    LocalModel,
    is_likely_otp,
    rule_classify,
)
from parser import parse_export_text
from store import Store


class ClassifierTests(unittest.TestCase):
    def test_otp_requires_meaning_and_code(self):
        self.assertTrue(is_likely_otp("【虚构甲】验证码 A7K9，仅用于本次测试"))
        self.assertTrue(is_likely_otp("【虚构甲】验证码123456，仅用于本次测试"))
        self.assertFalse(is_likely_otp("虚构订单号 123456 已发货"))
        self.assertFalse(is_likely_otp("请输入验证码完成虚构操作"))
        self.assertFalse(is_likely_otp("虚构金额 123456 元"))

    def test_signature_labels_and_unsigned_stays_pending(self):
        signed = rule_classify("【虚构平台】验证码123456")
        unsigned = rule_classify("您的验证码654321")
        self.assertEqual("LABELED", signed.status)
        self.assertEqual("虚构平台", signed.platform_name)
        self.assertEqual("RULE", signed.source)
        self.assertEqual("PENDING_LABEL", unsigned.status)
        self.assertIsNone(unsigned.platform_name)

    def test_generic_signature_is_not_a_platform(self):
        result = rule_classify("【验证码】123456 是你的验证码")
        self.assertEqual("PENDING_LABEL", result.status)

    def test_same_redacted_shape_shares_features(self):
        extractor = FeatureExtractor(bytes([1]) * 32)
        first = extractor.extract("【虚构甲】验证码 483921，日期 2026-01-02，访问 https://invalid.example/x?t=secret")
        same = extractor.extract("【虚构甲】验证码 739105，日期 2027-03-04，访问 https://invalid.example/x?t=other")
        other_key = FeatureExtractor(bytes([2]) * 32).extract(
            "【虚构甲】验证码 483921，日期 2026-01-02，访问 https://invalid.example/x?t=secret"
        )
        self.assertEqual(first, same)
        self.assertNotEqual(first, other_key)
        self.assertTrue(all(1 <= count <= 3 for count in first.values()))
        self.assertTrue(all(0 <= bucket < 16384 for bucket in first))

    def test_model_does_not_accept_a_single_class(self):
        model = LocalModel()
        features = {7: 1}
        model.learn(1, 1, features)
        model.learn(2, 1, features)
        label, accepted = model.predict(features)
        self.assertEqual(1, label)
        self.assertFalse(accepted)

    def test_export_text_keeps_slot_and_multiline_body(self):
        text = """卡槽: 卡槽1
号码: 无
本批: 1/1
条数: 2
----
时间: 2026-01-01 00:00:00
方向: 接收
对方: 10690000
内容:
【虚构平台】验证码123456
----
时间: 2026-01-02 00:00:00
方向: 接收
对方: 10690000
内容:
第一行
第二行
"""
        messages = parse_export_text(text)
        self.assertEqual(["卡槽1", "卡槽1"], [item.slot for item in messages])
        self.assertEqual("【虚构平台】验证码123456", messages[0].body)
        self.assertEqual("第一行\n第二行", messages[1].body)

    def test_store_classifies_and_learns_a_human_label(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp))
            text = """卡槽: 卡槽2
号码: 无
本批: 1/1
条数: 2
----
时间: 2026-01-01 00:00:00
方向: 接收
对方: 1069
内容:
【虚构平台】验证码123456
----
时间: 2026-01-02 00:00:00
方向: 接收
对方: 1069
内容:
您的验证码654321
"""
            imported = store.import_messages(parse_export_text(text))
            self.assertEqual(2, imported["inserted"])
            counts = store.classify_all()
            self.assertEqual(1, counts["LABELED"])
            self.assertEqual(1, counts["PENDING_LABEL"])
            pending = store.messages(None, None, "PENDING_LABEL")
            self.assertEqual(1, len(pending))
            store.label_messages([pending[0]["id"]], "虚构平台")
            self.assertEqual([], store.messages(None, None, "PENDING_LABEL"))
            labeled = store.messages("卡槽2", None, "LABELED")
            self.assertEqual({"虚构平台"}, {item["platform_name"] for item in labeled})


if __name__ == "__main__":
    unittest.main()
