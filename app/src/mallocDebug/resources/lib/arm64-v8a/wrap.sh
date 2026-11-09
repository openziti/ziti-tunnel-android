#!/system/bin/sh
# Packaged only with -PmallocDebug (see app/build.gradle.kts): records an allocation backtrace for every malloc,
# which `adb shell dumpsys meminfo --unreachable <pid>` then prints for each unreachable block.
LIBC_DEBUG_MALLOC_OPTIONS=backtrace=32 logwrapper "$@"
