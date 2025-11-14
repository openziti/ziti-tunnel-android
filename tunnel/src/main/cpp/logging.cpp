/*
 * Copyright (c) NetFoundry Inc
 * SPDX-License-Identifier: Apache-2.0
 *
 */
#include <inttypes.h>
#include <stdio.h>

#include <android/log.h>
#include <jni.h>
#include <uv.h>

#include <ziti/ziti_log.h>

#define LOG_FMT(msg_fmt) "[%9" PRIu64 ".%03" PRIu64 "] %c %.30s: " msg_fmt "\n"

static char log_dir[FILENAME_MAX];

static FILE *logfile;
static char levels[] = {
        'N', 'E', 'W', 'I', 'D', 'V', 'T'
};

static int log_priorities[] = {
        ANDROID_LOG_SILENT,
        ANDROID_LOG_ERROR,
        ANDROID_LOG_WARN,
        ANDROID_LOG_INFO,
        ANDROID_LOG_DEBUG,
        ANDROID_LOG_VERBOSE,
        ANDROID_LOG_VERBOSE,
};

class TimeKeeper {
public:
    TimeKeeper() {
        uv_timespec64_t now;
        uv_clock_gettime(UV_CLOCK_MONOTONIC, &now);
        start = now.tv_sec * 1000 + now.tv_nsec / 1e6;
    }

    uint64_t elapsedMillis() {
        uv_timespec64_t now;
        uv_clock_gettime(UV_CLOCK_MONOTONIC, &now);
        uint64_t millis = now.tv_sec * 1000 + now.tv_nsec / 1e6 - start;
        return millis;
    }

private:
    uint64_t start;
};

static TimeKeeper keeper;

void android_logger(int level, const char *loc, const char *msg, size_t msglen) {
    static_assert(sizeof levels / sizeof levels[0] > TRACE, "fix log level labels");
    static_assert(sizeof log_priorities / sizeof log_priorities[0] > TRACE, "fix log level labels");

    if (level < 0) level = ERROR;
    if (level > TRACE) level = TRACE;

    int pri = log_priorities[level];
    __android_log_print(pri, loc, "%.*s", (int) msglen, msg);

    if (logfile != nullptr) {
        char l = levels[level];

        uint64_t elapsed = keeper.elapsedMillis();
        fprintf(logfile, LOG_FMT("%.*s"),
                elapsed / 1000, elapsed % 1000,
                l, loc, (int) msglen, msg);
    }
}

static void createLogFile() {
    char path[FILENAME_MAX];
    time_t now = {};
    tm t = {};

    time(&now);
    gmtime_r(&now, &t);
    snprintf(path, sizeof(path), "%s/ziti-%4d%02d%02d-%02d%02d.log",
             log_dir, t.tm_year + 1900, t.tm_mon + 1, t.tm_mday, t.tm_hour, t.tm_min);

    FILE *lf = fopen(path, "a");
    if (lf != nullptr) {
        if (logfile != nullptr) {
            fflush(logfile);
            fclose(logfile);
        }
        logfile = lf;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_openziti_log_NativeLog_setLogLevel(JNIEnv *env, jclass clazz, jint level) {
}

extern "C"
JNIEXPORT void JNICALL
Java_org_openziti_log_NativeLog_setupLogging0(JNIEnv *env, jclass thiz, jstring dir) {
    char path[FILENAME_MAX];
    time_t now = {};
    tm t = {};

    time(&now);
    gmtime_r(&now, &t);

    const char *dir_str = env->GetStringUTFChars(dir, nullptr);
    snprintf(log_dir, sizeof(log_dir), "%s", dir_str);
    env->ReleaseStringUTFChars(dir, dir_str);
    snprintf(path, sizeof(path), "%s/ziti-%4d%02d%02d-%02d%02d.log",
             dir_str, t.tm_year + 1900, t.tm_mon + 1, t.tm_mday, t.tm_hour, t.tm_min);

    logfile = fopen(path, "a");
}

extern "C"
JNIEXPORT void JNICALL
Java_org_openziti_log_NativeLog_logNative(JNIEnv *env, jclass clazz,
                                          jint priority, jstring tag, jstring msg) {
    if (logfile != nullptr) {
        switch ((android_LogPriority) priority) {
            case ANDROID_LOG_UNKNOWN:
            case ANDROID_LOG_SILENT:
                return;
            case ANDROID_LOG_FATAL:
            case ANDROID_LOG_ERROR:
                priority = ERROR;
                break;
            case ANDROID_LOG_WARN:
                priority = WARN;
                break;
            case ANDROID_LOG_DEFAULT:
            case ANDROID_LOG_INFO:
                priority = INFO;
                break;
            case ANDROID_LOG_DEBUG:
                priority = DEBUG;
                break;
            case ANDROID_LOG_VERBOSE:
                priority = VERBOSE;
                break;
        }
        const char *t = env->GetStringUTFChars(tag, nullptr);
        if (priority < ziti_log_level("app", t)) {
            const char *m = env->GetStringUTFChars(msg, nullptr);

            char l = levels[priority];
            uint64_t elapsed = keeper.elapsedMillis();

            fprintf(logfile, LOG_FMT("%s"),
                    elapsed / 1000, elapsed % 1000,
                    l, t, m);

            env->ReleaseStringUTFChars(msg, m);
        }
        env->ReleaseStringUTFChars(tag, t);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_openziti_log_NativeLog_startNewFile(JNIEnv *env, jclass clazz) {
    createLogFile();
}