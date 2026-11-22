/* NDK r26+ <time.h> declares the timezone_t type at every API level, but
 * tzalloc()/mktime_z()/localtime_rz() only exist from API 35.  Gnulib (in
 * Guile) sees timezone_t and assumes the functions exist.  Guile only uses
 * them through nstrftime() with tz == NULL, i.e. UTC, so map them onto the
 * classic functions with gnulib's semantics (NULL tz means UTC; any other
 * tz is treated as the process's local time zone). */
#pragma once
#include_next <time.h>

#if defined(__ANDROID__) && __ANDROID_API__ < 35
static inline time_t lilyengine_mktime_z(timezone_t tz, struct tm *tm) {
    return tz ? mktime(tm) : timegm(tm);
}
static inline struct tm *lilyengine_localtime_rz(timezone_t tz, time_t const *t,
                                                 struct tm *tm) {
    return tz ? localtime_r(t, tm) : gmtime_r(t, tm);
}
#define mktime_z lilyengine_mktime_z
#define localtime_rz lilyengine_localtime_rz
#endif
