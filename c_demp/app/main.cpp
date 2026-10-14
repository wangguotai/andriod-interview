// Demo entry point for cpp_learn.
//
// The interesting code lives in the `cpplearn` library (include/ + src/).
// Keep main.cpp thin: it is a place to experiment, not to hold logic.

#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

#include "cpplearn/math_utils.h"
#include "cpplearn/string_utils.h"

namespace {

void print_header(const std::string& title) {
    std::cout << "\n=== " << title << " ===\n";
}

void demo_math() {
    print_header("math_utils");

    constexpr std::int64_t a = 48;
    constexpr std::int64_t b = 18;
    std::cout << "gcd(" << a << ", " << b << ") = " << cpplearn::gcd(a, b) << '\n'
              << "lcm(" << a << ", " << b << ") = " << cpplearn::lcm(a, b) << '\n'
              << "is_prime(97)          = " << std::boolalpha << cpplearn::is_prime(97) << '\n';

    const std::vector<double> samples{1.0, 2.5, 3.5, 9.0};
    std::cout << "average(1, 2.5, 3.5, 9) = " << cpplearn::average(samples) << '\n';

    const std::vector<std::int64_t> primes = cpplearn::primes_up_to(30);
    std::cout << "primes <= 30:";
    for (const std::int64_t prime : primes) {
        std::cout << ' ' << prime;
    }
    std::cout << '\n';
}

void demo_strings() {
    print_header("string_utils");

    const std::string raw = "  alpha, beta ,gamma  ";
    const std::string trimmed = cpplearn::trim(raw);
    std::cout << "trim     : [" << trimmed << "]\n";

    std::vector<std::string> fields = cpplearn::split(trimmed, ',');
    for (std::string& field : fields) {  // mutable reference: modify in place
        field = cpplearn::trim(field);
    }
    std::cout << "split    : " << cpplearn::join(fields, " | ") << '\n'
              << "equals   : " << std::boolalpha << cpplearn::equals_ignore_case("Hello", "hELLo")
              << '\n'
              << "starts   : " << cpplearn::starts_with(trimmed, "alpha") << '\n'
              << "replace  : " << cpplearn::replace_all(trimmed, "beta", "BETA") << '\n'
              << "truncate : " << cpplearn::truncate(trimmed, 12) << '\n';
}

}  // namespace

int main() {
    std::cout << "cpp_learn -- C++" << __cplusplus << " demo\n";
    demo_math();
    demo_strings();
    std::cout << "\ndone.\n";
    return 0;
}
