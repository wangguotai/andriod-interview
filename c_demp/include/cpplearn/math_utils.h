#pragma once

#include <cstdint>
#include <vector>

namespace cpplearn {

/// Greatest common divisor (Euclid's algorithm).
/// Returns a non-negative value; gcd(0, 0) == 0.
[[nodiscard]] std::int64_t gcd(std::int64_t a, std::int64_t b);

/// Least common multiple. Returns 0 if either argument is 0.
[[nodiscard]] std::int64_t lcm(std::int64_t a, std::int64_t b);

/// True when `n` is a prime number.
[[nodiscard]] bool is_prime(std::int64_t n);

/// Returns all primes <= n in ascending order (sieve of Eratosthenes).
[[nodiscard]] std::vector<std::int64_t> primes_up_to(std::int64_t n);

/// Average of a non-empty range. Throws std::invalid_argument when empty.
[[nodiscard]] double average(const std::vector<double>& values);

}  // namespace cpplearn
