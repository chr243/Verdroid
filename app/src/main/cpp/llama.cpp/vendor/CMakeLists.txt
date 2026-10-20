# mtmd needs these even when common is not built
add_subdirectory(hash)
add_subdirectory(nlohmann)
add_subdirectory(sheredom)

# only used by common
if (LLAMA_BUILD_COMMON)
    add_subdirectory(cpp-httplib)
endif()
