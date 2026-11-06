"""Deterministic safety rules with the original Java wording and order."""

import re

from .baseline_text import EMERGENCY_ACTION_TERMS, EMERGENCY_CAUTION, EMERGENCY_NOTICE, EMERGENCY_TERMS, GUARDED_RESPONSE
from .compat import java_blank, java_trim

DIAGNOSIS = re.compile(r"(你|您)(已经|就是|患有|得了|确诊为)|诊断为|确诊为")
PRESCRIPTION = re.compile(r"处方为|给你开药|(?:请|建议)服用|每日服用\s*\d+|增加剂量|减少剂量|调整剂量|停用[^\n\r\x85\u2028\u2029]*药|换用[^\n\r\x85\u2028\u2029]*药", re.ASCII)


def assess(message: str | None) -> str | None:
    normalized = re.sub(r"[\s，。！？、；：:,.!?]", "", (message or "").lower(), flags=re.ASCII)
    if java_blank(normalized) or not any(term in normalized for term in EMERGENCY_TERMS):
        return None
    return EMERGENCY_NOTICE if any(term in normalized for term in EMERGENCY_ACTION_TERMS) else EMERGENCY_CAUTION


def guard_response(response: str | None) -> str | None:
    if java_blank(response):
        return response
    content = java_trim(response)
    return GUARDED_RESPONSE if DIAGNOSIS.search(content) or PRESCRIPTION.search(content) else content
