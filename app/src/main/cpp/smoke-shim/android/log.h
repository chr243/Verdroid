#pragma once
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define __android_log_print(l, t, ...) (fprintf(stderr, __VA_ARGS__), 0)
