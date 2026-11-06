"""Port of ConversationMemoryPolicy, including its original token estimate."""

from .compat import java_blank, java_trim, java_whitespace, utf16_length, utf16_prefix
from .models import ConversationMessage, RuntimeOptions


def estimate_tokens(content: str) -> int:
    tokens = 0
    ascii_run = 0
    for char in content:
        point = ord(char)
        if 0x2E80 <= point <= 0x9FFF or 0xF900 <= point <= 0xFAFF or 0x20000 <= point <= 0x3134F:
            tokens += 1
            ascii_run = 0
        elif java_whitespace(char):
            if ascii_run > 0:
                tokens += (ascii_run + 3) // 4
                ascii_run = 0
        else:
            ascii_run += 1
    if ascii_run > 0:
        tokens += (ascii_run + 3) // 4
    return max(1, tokens)


def truncate_to_tokens(content: str, maximum: int) -> str:
    if estimate_tokens(content) <= maximum:
        return content
    result = ""
    for char in content:
        if estimate_tokens(result + char) > maximum:
            break
        result += char
    return result or utf16_prefix(content, 1)


def prepare_history(history: list[ConversationMessage], options: RuntimeOptions) -> list[ConversationMessage]:
    if not history or options.maxHistoryMessages <= 0 or options.maxHistoryTokens <= 0:
        return []
    normalized = []
    for message in history:
        if message.role not in {"user", "assistant"}:
            continue
        content = java_trim(message.content)
        if java_blank(content):
            continue
        if options.maxHistoryMessageCharacters > 0 and utf16_length(content) > options.maxHistoryMessageCharacters:
            content = utf16_prefix(content, options.maxHistoryMessageCharacters) + "…"
        # Java substring may preserve an unmatched surrogate; this is trusted derived state.
        normalized.append(message.model_copy(update={"content": content}))
    result = []
    used = 0
    for message in reversed(normalized):
        if len(result) >= options.maxHistoryMessages:
            break
        tokens = estimate_tokens(message.content)
        if result and used + tokens > options.maxHistoryTokens:
            break
        if not result and tokens > options.maxHistoryTokens:
            message = message.model_copy(update={"content": truncate_to_tokens(message.content, options.maxHistoryTokens)})
            tokens = estimate_tokens(message.content)
        result.insert(0, message)
        used += tokens
    return result
