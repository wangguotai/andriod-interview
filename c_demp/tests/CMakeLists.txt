# ---------------------------------------------------------------------------
# Test target wiring
#
# GoogleTest is fetched automatically by default, so a fresh clone can run
# tests without installing anything. Set CPPLEARN_USE_SYSTEM_GTEST=ON to link
# an already-installed copy instead (useful offline / in CI caches).
# ---------------------------------------------------------------------------
option(CPPLEARN_USE_SYSTEM_GTEST "Use an installed GoogleTest instead of fetching it" OFF)

# Upstream lives on GitHub; the mirror below is used because it is reachable in
# networks where github.com is blocked. Override either variable to point at the
# source you prefer, e.g.:
#   cmake -S . -B build -DCPPLEARN_GTEST_REPOSITORY=https://github.com/google/googletest.git
set(CPPLEARN_GTEST_REPOSITORY "https://gitee.com/mirrors/googletest.git"
    CACHE STRING "Git repository used to fetch GoogleTest")
set(CPPLEARN_GTEST_TAG "v1.15.2" CACHE STRING "GoogleTest git tag to check out")

if(CPPLEARN_USE_SYSTEM_GTEST)
    find_package(GTest REQUIRED)
    set(CPPLEARN_GTEST_TARGET GTest::gtest_main)
    message(STATUS "Using system GoogleTest")
else()
    include(FetchContent)

    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)  # keep MSVC runtime consistent
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(
        googletest
        GIT_REPOSITORY "${CPPLEARN_GTEST_REPOSITORY}"
        GIT_TAG "${CPPLEARN_GTEST_TAG}"
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(googletest)
    set(CPPLEARN_GTEST_TARGET gtest_main)
    message(STATUS "Fetching GoogleTest ${CPPLEARN_GTEST_TAG} from ${CPPLEARN_GTEST_REPOSITORY}")
endif()

add_executable(cpplearn_tests
    math_utils_test.cpp
    string_utils_test.cpp
)
target_link_libraries(cpplearn_tests PRIVATE cpplearn ${CPPLEARN_GTEST_TARGET})
target_link_libraries(cpplearn_tests PRIVATE cpplearn_warnings)

include(GoogleTest)
# A generous discovery timeout keeps builds working under slow start-up
# conditions (sanitizers, cold caches, network-mounted volumes).
gtest_discover_tests(cpplearn_tests DISCOVERY_TIMEOUT 30)
