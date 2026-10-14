#include <stdexcept>
#include <vector>

#include <gtest/gtest.h>

#include "cpplearn/math_utils.h"

namespace {

TEST(GcdTest, HandlesPositiveNumbers) {
    EXPECT_EQ(cpplearn::gcd(48, 18), 6);
    EXPECT_EQ(cpplearn::gcd(17, 5), 1);
    EXPECT_EQ(cpplearn::gcd(100, 100), 100);
}

TEST(GcdTest, IsSignAgnostic) {
    EXPECT_EQ(cpplearn::gcd(-48, 18), 6);
    EXPECT_EQ(cpplearn::gcd(48, -18), 6);
    EXPECT_EQ(cpplearn::gcd(-48, -18), 6);
}

TEST(GcdTest, HandlesZero) {
    EXPECT_EQ(cpplearn::gcd(0, 0), 0);
    EXPECT_EQ(cpplearn::gcd(7, 0), 7);
    EXPECT_EQ(cpplearn::gcd(0, 7), 7);
}

TEST(LcmTest, ComputesLeastCommonMultiple) {
    EXPECT_EQ(cpplearn::lcm(4, 6), 12);
    EXPECT_EQ(cpplearn::lcm(21, 6), 42);
}

TEST(LcmTest, ReturnsZeroWhenEitherArgumentIsZero) {
    EXPECT_EQ(cpplearn::lcm(0, 5), 0);
    EXPECT_EQ(cpplearn::lcm(5, 0), 0);
}

// Parameterised-ish coverage via a table: the loop keeps the intent obvious.
TEST(IsPrimeTest, MatchesKnownValues) {
    struct Case {
        std::int64_t input;
        bool expected;
    };
    const std::vector<Case> cases{
        {-7, false}, {0, false}, {1, false},  {2, true},  {3, true},    {4, false},
        {5, true},   {9, false}, {25, false}, {97, true}, {100, false}, {7919, true},
    };

    for (const Case& test_case : cases) {
        EXPECT_EQ(cpplearn::is_prime(test_case.input), test_case.expected)
            << "is_prime(" << test_case.input << ")";
    }
}

TEST(PrimesUpToTest, ListsPrimesInAscendingOrder) {
    const std::vector<std::int64_t> expected{2, 3, 5, 7, 11, 13, 17, 19, 23, 29};
    EXPECT_EQ(cpplearn::primes_up_to(30), expected);
}

TEST(PrimesUpToTest, ReturnsEmptyBelowTwo) {
    EXPECT_TRUE(cpplearn::primes_up_to(1).empty());
    EXPECT_TRUE(cpplearn::primes_up_to(0).empty());
    EXPECT_TRUE(cpplearn::primes_up_to(-10).empty());
}

TEST(PrimesUpToTest, HandlesBoundaryInclusive) {
    EXPECT_EQ(cpplearn::primes_up_to(2), std::vector<std::int64_t>{2});
    EXPECT_EQ(cpplearn::primes_up_to(3), (std::vector<std::int64_t>{2, 3}));
}

TEST(AverageTest, ComputesArithmeticMean) {
    EXPECT_DOUBLE_EQ(cpplearn::average({1.0, 2.0, 3.0, 4.0}), 2.5);
    EXPECT_DOUBLE_EQ(cpplearn::average({5.0}), 5.0);
}

TEST(AverageTest, ThrowsOnEmptyInput) {
    // static_cast<void> silences -Wunused-result for the [[nodiscard]] return
    // value inside the EXPECT_THROW macro.
    EXPECT_THROW(static_cast<void>(cpplearn::average({})), std::invalid_argument);
}

}  // namespace
