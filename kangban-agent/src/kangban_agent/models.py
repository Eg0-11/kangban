"""Normalized wire types matching docs/contracts/python-agent-v1.schema.json."""

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .compat import java_blank

Long = Annotated[int, Field(ge=-(2**63), le=2**63 - 1)]
Int32 = Annotated[int, Field(ge=-(2**31), le=2**31 - 1)]
ToolStatus = Literal["SUCCESS", "BLOCKED", "FAILED"]


class WireModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class AgentExecutionContext(WireModel):
    actorUserId: Long
    subjectUserId: Long
    memberId: Long | None
    sessionId: Long
    runId: str = Field(min_length=1)
    traceId: str = Field(min_length=1)
    issuedAtEpochSecond: Long
    expiresAtEpochSecond: Long

    @model_validator(mode="after")
    def valid_context(self):
        if java_blank(self.runId) or java_blank(self.traceId) or self.expiresAtEpochSecond <= self.issuedAtEpochSecond:
            raise ValueError("Invalid execution context")
        return self


class ConversationMessage(WireModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1)

    @field_validator("role", "content")
    @classmethod
    def not_blank(cls, value: str):
        if java_blank(value):
            raise ValueError("Message fields must not be blank")
        return value


class AgentRequest(WireModel):
    context: AgentExecutionContext
    message: str = Field(min_length=1)
    patientContextJson: str | None
    history: list[ConversationMessage]

    @field_validator("message")
    @classmethod
    def not_blank(cls, value: str):
        if java_blank(value):
            raise ValueError("Message must not be blank")
        return value


class RuntimeOptions(WireModel):
    enabled: bool
    modelToolCallingEnabled: bool
    ragEnabled: bool
    maxIterations: Int32
    maxHistoryMessages: Int32
    maxHistoryTokens: Int32
    maxHistoryMessageCharacters: Int32


class RunEnvelope(WireModel):
    contractVersion: Literal["1"]
    request: AgentRequest
    options: RuntimeOptions


class Citation(WireModel):
    documentId: str
    title: str
    version: str | None
    pageNumber: Int32 | None
    section: str | None
    source: str | None
    updatedAt: str | None
    scope: Literal["PUBLIC", "PRIVATE"]


class ActionProposal(WireModel):
    id: str
    type: str
    status: Literal["DRAFT", "PENDING_CONFIRMATION", "CONFIRMED", "EXECUTING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"]
    parameters: dict[str, Any]
    payloadHash: str | None
    expiresAt: str | None


class AgentToolTrace(WireModel):
    toolName: str
    status: ToolStatus
    iteration: int = Field(ge=1)
    elapsedMs: int = Field(ge=0)
    errorCode: str


class AgentResponse(WireModel):
    content: str = Field(min_length=1)
    runId: str
    citations: list[Citation] = Field(default_factory=list)
    actions: list[ActionProposal] = Field(default_factory=list)
    toolTraces: list[AgentToolTrace] = Field(default_factory=list)


class RunResultEnvelope(WireModel):
    contractVersion: Literal["1"] = "1"
    response: AgentResponse
    outcome: Literal["success", "safety_emergency", "action_handled"]


class AiToolDefinition(WireModel):
    name: str
    description: str
    parameters: dict[str, Any]


class AgentToolCall(WireModel):
    name: str
    arguments: dict[str, Any]


class AgentToolResult(WireModel):
    toolName: str
    status: ToolStatus
    content: str
    errorCode: str


class ToolExecutionResponse(WireModel):
    result: AgentToolResult
    elapsedMs: int = Field(ge=0)


class KnowledgeSearchHit(WireModel):
    content: str
    score: float
    citation: Citation


class RagSearchResult(WireModel):
    context: str = ""
    hits: list[KnowledgeSearchHit] = Field(default_factory=list)


class ProposalDecision(WireModel):
    handled: bool
    content: str | None
    action: ActionProposal | None


class InternalError(WireModel):
    contractVersion: Literal["1"] = "1"
    code: str
    message: str
    runId: str | None
    retryable: bool


class HealthResponse(WireModel):
    status: Literal["ok"] = "ok"
    contractVersion: Literal["1"] = "1"
