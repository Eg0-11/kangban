"""Java 17 string semantics used by the existing deterministic policies."""

import unicodedata


def java_trim(value: str) -> str:
    return value.strip("".join(chr(i) for i in range(33)))


def java_whitespace(char: str) -> bool:
    return char in "\t\n\v\f\r\x1c\x1d\x1e\x1f" or (
        unicodedata.category(char) in {"Zs", "Zl", "Zp"} and char not in "\u00a0\u2007\u202f"
    )


def java_blank(value: str | None) -> bool:
    return value is None or all(java_whitespace(char) for char in value)


def utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le", errors="surrogatepass")) // 2


def utf16_prefix(value: str, length: int) -> str:
    return value.encode("utf-16-le", errors="surrogatepass")[: length * 2].decode(
        "utf-16-le", errors="surrogatepass"
    )


def java_wire_text(value: str) -> str:
    # BodyPublishers.ofString uses UTF-8 replacement for an unmatched UTF-16 surrogate.
    return value.encode("utf-8", errors="replace").decode("utf-8")
