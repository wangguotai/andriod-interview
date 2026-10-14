#include <stdexcept>
#include <string>
#include <vector>

#include <gtest/gtest.h>

#include "cpplearn/string_utils.h"

namespace {

TEST(TrimTest, RemovesSurroundingWhitespace) {
    EXPECT_EQ(cpplearn::trim("  hello  "), "hello");
    EXPECT_EQ(cpplearn::trim("\t\nmixed\r\n"), "mixed");
}

TEST(TrimTest, LeavesInnerWhitespaceUntouched) {
    EXPECT_EQ(cpplearn::trim("  a b  "), "a b");
}

TEST(TrimTest, HandlesAllWhitespaceAndEmptyInput) {
    EXPECT_EQ(cpplearn::trim("   "), "");
    EXPECT_EQ(cpplearn::trim(""), "");
}

TEST(SplitTest, SplitsOnDelimiter) {
    EXPECT_EQ(cpplearn::split("a,b,c", ','), (std::vector<std::string>{"a", "b", "c"}));
}

TEST(SplitTest, PreservesEmptyFields) {
    EXPECT_EQ(cpplearn::split("a,,c", ','), (std::vector<std::string>{"a", "", "c"}));
    EXPECT_EQ(cpplearn::split(",", ','), (std::vector<std::string>{"", ""}));
}

TEST(SplitTest, ReturnsSingleFieldWhenDelimiterAbsent) {
    EXPECT_EQ(cpplearn::split("abc", ','), std::vector<std::string>{"abc"});
}

TEST(JoinTest, InsertsDelimiterBetweenParts) {
    EXPECT_EQ(cpplearn::join({"a", "b", "c"}, "-"), "a-b-c");
}

TEST(JoinTest, HandlesDegenerateInputs) {
    EXPECT_EQ(cpplearn::join({}, "-"), "");
    EXPECT_EQ(cpplearn::join({"solo"}, "-"), "solo");
    EXPECT_EQ(cpplearn::join({"a", "b"}, ""), "ab");
}

TEST(SplitJoinTest, RoundTrips) {
    const std::string original = "one|two|three";
    const std::vector<std::string> parts = cpplearn::split(original, '|');
    EXPECT_EQ(cpplearn::join(parts, "|"), original);
}

TEST(EqualsIgnoreCaseTest, ComparesAsciiCaseInsensitively) {
    EXPECT_TRUE(cpplearn::equals_ignore_case("Hello", "hELLo"));
    EXPECT_TRUE(cpplearn::equals_ignore_case("ABC", "abc"));
    EXPECT_FALSE(cpplearn::equals_ignore_case("abc", "abd"));
}

TEST(EqualsIgnoreCaseTest, RespectsLength) {
    EXPECT_FALSE(cpplearn::equals_ignore_case("abc", "ab"));
    EXPECT_TRUE(cpplearn::equals_ignore_case("", ""));
}

TEST(StartsWithTest, DetectsPrefix) {
    EXPECT_TRUE(cpplearn::starts_with("cpplearn", "cpp"));
    EXPECT_TRUE(cpplearn::starts_with("cpplearn", ""));
    EXPECT_TRUE(cpplearn::starts_with("cpplearn", "cpplearn"));
    EXPECT_FALSE(cpplearn::starts_with("cpp", "cpplearn"));
    EXPECT_FALSE(cpplearn::starts_with("cpplearn", "learn"));
}

TEST(ReplaceAllTest, ReplacesEveryOccurrence) {
    EXPECT_EQ(cpplearn::replace_all("a-b-c", "-", "+"), "a+b+c");
    EXPECT_EQ(cpplearn::replace_all("aaa", "a", "bb"), "bbbbbb");
    EXPECT_EQ(cpplearn::replace_all("abc", "z", "y"), "abc");
}

TEST(ReplaceAllTest, HandlesNonOverlappingMatches) {
    EXPECT_EQ(cpplearn::replace_all("aaaa", "aa", "b"), "bb");
}

TEST(ReplaceAllTest, ThrowsWhenNeedleIsEmpty) {
    EXPECT_THROW(static_cast<void>(cpplearn::replace_all("abc", "", "x")), std::invalid_argument);
}

TEST(TruncateTest, ReturnsTextUnchangedWhenShortEnough) {
    EXPECT_EQ(cpplearn::truncate("hello", 5), "hello");
    EXPECT_EQ(cpplearn::truncate("hello", 99), "hello");
}

TEST(TruncateTest, AppendsEllipsisWhenCut) {
    EXPECT_EQ(cpplearn::truncate("hello world", 8), "hello...");
    EXPECT_EQ(cpplearn::truncate("abcdef", 4), "a...");
}

TEST(TruncateTest, DoesNotGrowPastMaxLengthForTinyLimits) {
    EXPECT_EQ(cpplearn::truncate("abcdef", 3), "abc");
    EXPECT_EQ(cpplearn::truncate("abcdef", 0), "");
}

}  // namespace
