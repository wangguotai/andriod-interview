#include <cmath>
#include <stdexcept>

#include "cpplearn/math_utils.h"

namespace cpplearn {
namespace {

// Anonymous namespace == internal linkage: helpers stay private to this file.
[[nodiscard]] std::int64_t abs_i64(std::int64_t value) noexcept {
    return value < 0 ? -value : value;
}

}  // namespace

std::int64_t gcd(std::int64_t a, std::int64_t b) {
    a = abs_i64(a);
    b = abs_i64(b);
    while (b != 0) {
        const std::int64_t remainder = a % b;
        a = b;
        b = remainder;
    }
    return a;
}

std::int64_t lcm(std::int64_t a, std::int64_t b) {
    if (a == 0 || b == 0) {
        return 0;
    }
    return abs_i64(a / gcd(a, b) * b);
}

bool is_prime(std::int64_t n) {
    if (n < 2) {
        return false;
    }
    if (n < 4) {
        return true;  // 2 and 3
    }
    if (n % 2 == 0) {
        return false;
    }
    for (std::int64_t divisor = 3; divisor <= n / divisor; divisor += 2) {
        if (n % divisor == 0) {
            return false;
        }
    }
    return true;
}

std::vector<std::int64_t> primes_up_to(std::int64_t n) {
    std::vector<std::int64_t> primes;
    if (n < 2) {
        return primes;
    }

    std::vector<bool> is_composite(static_cast<std::size_t>(n) + 1, false);
    for (std::int64_t candidate = 2; candidate <= n; ++candidate) {
        if (is_composite[static_cast<std::size_t>(candidate)]) {
            continue;
        }
        primes.push_back(candidate);
        // Start at candidate * candidate; smaller multiples are already marked.
        for (std::int64_t multiple = candidate * candidate; multiple <= n; multiple += candidate) {
            is_composite[static_cast<std::size_t>(multiple)] = true;
        }
    }
    return primes;
}

double average(const std::vector<double>& values) {
    if (values.empty()) {
        throw std::invalid_argument("average(): values must not be empty");
    }

    double total = 0.0;
    for (const double value : values) {
        total += value;
    }
    return total / static_cast<double>(values.size());
}

}  // namespace cpplearn
