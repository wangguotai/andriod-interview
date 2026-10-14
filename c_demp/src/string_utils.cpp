#include <algorithm>
#include <stdexcept>

#include "cpplearn/string_utils.h"

#include "string_utils_impl.h"

namespace cpplearn {

std::string trim(std::string_view text) {
    std::size_t begin = 0;
    std::size_t end = text.size();

    while (begin < end && detail::is_ascii_space(text[begin])) {
        ++begin;
    }
    while (end > begin && detail::is_ascii_space(text[end - 1])) {
        --end;
    }
    return std::string(text.substr(begin, end - begin));
}

std::vector<std::string> split(std::string_view text, char delimiter) {
    std::vector<std::string> parts;
    std::size_t start = 0;

    while (true) {
        const std::size_t position = text.find(delimiter, start);
        if (position == std::string_view::npos) {
            parts.emplace_back(text.substr(start));
            break;
        }
        parts.emplace_back(text.substr(start, position - start));
        start = position + 1;
    }
    return parts;
}

std::string join(const std::vector<std::string>& parts, std::string_view delimiter) {
    std::string result;
    for (std::size_t index = 0; index < parts.size(); ++index) {
        if (index != 0) {
            result.append(delimiter);
        }
        result.append(parts[index]);
    }
    return result;
}

bool equals_ignore_case(std::string_view lhs, std::string_view rhs) {
    if (lhs.size() != rhs.size()) {
        return false;
    }
    for (std::size_t index = 0; index < lhs.size(); ++index) {
        if (detail::ascii_lower(lhs[index]) != detail::ascii_lower(rhs[index])) {
            return false;
        }
    }
    return true;
}

bool starts_with(std::string_view text, std::string_view prefix) {
    return text.size() >= prefix.size() && text.compare(0, prefix.size(), prefix) == 0;
}

std::string replace_all(std::string_view text, std::string_view from, std::string_view to) {
    if (from.empty()) {
        throw std::invalid_argument("replace_all(): 'from' must not be empty");
    }

    std::string result;
    result.reserve(text.size());

    std::size_t start = 0;
    while (true) {
        const std::size_t position = text.find(from, start);
        if (position == std::string_view::npos) {
            result.append(text.substr(start));
            break;
        }
        result.append(text.substr(start, position - start));
        result.append(to);
        start = position + from.size();
    }
    return result;
}

std::string truncate(std::string_view text, std::size_t max_length) {
    constexpr std::size_t kEllipsisLength = 3;
    if (text.size() <= max_length) {
        return std::string(text);
    }
    if (max_length <= kEllipsisLength) {
        return std::string(text.substr(0, max_length));
    }
    return std::string(text.substr(0, max_length - kEllipsisLength)) + "...";
}

}  // namespace cpplearn
