#pragma once

// Private header: lives in src/ and is NOT installed or exposed to consumers.
// It shows how to share helpers between translation units without leaking them
// through the public API in include/.
//
// Style note: private headers use .h (not .hpp) to make their role visible.

namespace cpplearn::detail {

/// ASCII-only lowercasing. Used instead of std::tolower to avoid locale issues
/// and the undefined behaviour of passing negative char values to <cctype>.
[[nodiscard]] constexpr char ascii_lower(char ch) noexcept {
    return (ch >= 'A' && ch <= 'Z') ? static_cast<char>(ch - 'A' + 'a') : ch;
}

/// True for ' ', '\t', '\n', '\v', '\f', '\r'.
[[nodiscard]] constexpr bool is_ascii_space(char ch) noexcept {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\v' || ch == '\f' || ch == '\r';
}

}  // namespace cpplearn::detail
