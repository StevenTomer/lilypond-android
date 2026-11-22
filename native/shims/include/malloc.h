/* Bionic's <malloc.h> uses FILE without declaring it, which breaks when
 * gnulib's <stdio.h> wrapper is in the include chain (seen building Guile).
 * Same workaround as Termux's packages/guile/malloc.h. */
#pragma once
struct __sFILE;
typedef struct __sFILE FILE;
#include_next <malloc.h>
