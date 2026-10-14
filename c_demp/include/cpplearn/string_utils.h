#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace cpplearn {

/// Removes leading and trailing whitespace.
[[nodiscard]] std::string trim(std::string_view text);

/// Splits `text` on `delimiter`. Empty fields are preserved.
[[nodiscard]] std::vector<std::string> split(std::string_view text, char delimiter);

/// Joins `parts` with `delimiter`.
[[nodiscard]] std::string join(const std::vector<std::string>& parts, std::string_view delimiter);

/// Case-insensitive ASCII comparison.
[[nodiscard]] bool equals_ignore_case(std::string_view lhs, std::string_view rhs);

/// True when `text` starts with `prefix`.
[[nodiscard]] bool starts_with(std::string_view text, std::string_view prefix);

/// Replaces every occurrence of `from` with `to`. `from` must not be empty.
[[nodiscard]] std::string replace_all(std::string_view text, std::string_view from,
                                      std::string_view to);

/// Truncates to at most `max_length` characters, appending "..." when cut.
[[nodiscard]] std::string truncate(std::string_view text, std::size_t max_length);

}  // namespace cpplearn
