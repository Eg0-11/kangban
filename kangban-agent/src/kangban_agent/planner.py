"""Deterministic tool selection ported from AgentToolPlanner."""

import re

from .baseline_text import JAVA_HAN_RANGES
from .compat import java_trim
from .models import AgentToolCall

JAVA_DOT = r"[^\n\r\x85\u2028\u2029]"
HAN = "".join(f"\\U{start:08x}-\\U{end:08x}" for start, end in JAVA_HAN_RANGES)
QUERY_VERBS = ("查一下", "想查", "查询", "查找", "了解", "看看", "查", "找")


def contains_any(value: str, *keywords: str) -> bool:
    return any(keyword in value for keyword in keywords)


def public_hospital_query(query: str) -> bool:
    if contains_any(query, "天津医院", "医院信息", "医院地址", "医院等级", "三甲", "公共医院"):
        return True
    matches = re.fullmatch(JAVA_DOT + r"*(?:查一下|想查|查询|查找|了解|看看|查|找)" + JAVA_DOT + "{0,30}医院" + JAVA_DOT + "*", query)
    return bool(matches) and not contains_any(query, "我的医院", "我在医院", "住院", "病历", "报告", "检查", "诊断", "患者", "家属", "本人")


def public_keyword(message: str, kind: str) -> str:
    value = java_trim(message)
    start = -1
    length = 0
    for verb in QUERY_VERBS:
        candidate = value.rfind(verb)
        if candidate > start:
            start, length = candidate, len(verb)
    if start >= 0:
        value = value[start + length :]
    expressions = {
        "hospital": f"([{HAN}A-Za-z0-9]{{2,30}}医院)",
        "doctor": f"([{HAN}]{{2,10}}(?:医生|专家|医师))",
        "department": f"([{HAN}A-Za-z0-9]{{1,20}}科)",
    }
    match = re.search(expressions[kind], value)
    return match.group(1) if match else ""


def plan_tools(message: str, max_iterations: int) -> list[AgentToolCall]:
    query = java_trim(message).lower()
    calls = []
    if contains_any(query, "血压", "心率", "心跳", "血糖", "体重", "步数", "睡眠", "健康指标", "健康数据", "趋势", "测量", "异常"):
        arguments = {}
        for words, metric in [(('血压',), 'blood_pressure'), (('心率', '心跳'), 'heart_rate'),
                              (('血糖',), 'blood_glucose'), (('体重',), 'weight'),
                              (('步数',), 'steps'), (('睡眠',), 'sleep')]:
            if contains_any(query, *words):
                arguments["metric"] = metric
                break
        arguments["limit"] = 20
        calls.append(AgentToolCall(name="get_health_metrics", arguments=arguments))
    if contains_any(query, "用药", "吃药", "服药", "药物", "药品", "剂量", "漏服", "相互作用"):
        calls.append(AgentToolCall(name="get_active_medications", arguments={"limit": 20}))
    hospital = public_hospital_query(query)
    public = hospital or contains_any(query, "医生", "专家", "医师", "科室", "门诊")
    if not public and contains_any(query, "病历", "报告", "检查", "体检", "诊断", "医院"):
        calls.append(AgentToolCall(name="get_recent_medical_records", arguments={"limit": 5}))
    if hospital:
        arguments = {"keyword": public_keyword(message, "hospital")}
        if "三甲" in query:
            arguments["hospitalLevel"] = "三级甲等"
        calls.append(AgentToolCall(name="search_hospitals", arguments=arguments))
    elif contains_any(query, "医生", "专家", "医师"):
        calls.append(AgentToolCall(name="search_doctors", arguments={"keyword": public_keyword(message, "doctor")}))
    elif contains_any(query, "科室", "门诊"):
        calls.append(AgentToolCall(name="search_departments", arguments={"keyword": public_keyword(message, "department")}))
    if not calls:
        calls.append(AgentToolCall(name="get_patient_health_snapshot", arguments={}))
    return calls[: max(1, max_iterations)]
