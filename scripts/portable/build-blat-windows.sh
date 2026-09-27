#!/usr/bin/env bash
set -euo pipefail
# Run with the isolated Cygwin build toolchain; only the resulting runtime is distributed.
export PATH="/usr/bin:$PATH"
source_directory=$(cygpath -u "$1")
destination=$(cygpath -u "$2")
cd "$source_directory"
mkdir -p "$destination"
# get_thread_id already has a non-Linux fallback; Cygwin has no sys/syscall.h.
if grep -q '^#include <sys/syscall.h>$' lib/obscure.c; then
    sed -i '/^#include <sys\/syscall.h>$/d' lib/obscure.c
fi
make -C submodules/htslib config.h
# Local BLAT uses plain reference FASTA; HTTP support in HTSlib is unnecessary.
if grep -q '#define HAVE_LIBCURL ' submodules/htslib/config.h; then
    sed -i '/#define HAVE_LIBCURL /d' submodules/htslib/config.h
fi
make -j8 -C submodules/htslib libhts.a NONCONFIGURE_OBJS= LIBS='-lz -lm -lbz2 -llzma -lpthread' \
    'HTS_CFLAGS_SSE4=-msse4.1 -mssse3 -mpopcnt'
options=(MACHTYPE=x86_64 CONDA_BUILD=1 ZLIB=-lz PNGLIB=-lpng PNGINCL=-I/usr/include MYSQLINC=/unused MYSQLLIBS= MYSQLCONFIG=true \
    COPT=-O2 'CFLAGS=-std=c99 -fno-strict-aliasing -ffunction-sections -fdata-sections' \
    "L=$source_directory/submodules/htslib/libhts.a -Wl,--gc-sections -lpng -lz -lm -lbz2 -llzma -lssl -lcrypto -lpthread")
make -j8 -C lib "${options[@]}"
make -j8 -C jkOwnLib "${options[@]}"
make -j8 -C blat do_build "${options[@]}" "BINDIR=$destination"
strip "$destination/blat.exe"
