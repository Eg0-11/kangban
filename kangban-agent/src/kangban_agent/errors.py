"""Safe errors for internal HTTP; never attach provider payloads or credentials."""


class AgentError(Exception):
    def __init__(self, status: int, code: str, message: str, retryable: bool = False):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable


def unavailable() -> AgentError:
    return AgentError(503, "AGENT_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试。", True)


def no_content() -> AgentError:
    return AgentError(503, "EMPTY_RESPONSE", "AI 服务暂未返回有效内容，请稍后重试。", True)


def deadline_exceeded() -> AgentError:
    return AgentError(504, "DEADLINE_EXCEEDED", "AI 响应超时，请重试。", True)
