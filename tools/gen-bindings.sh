#!/bin/sh
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/mod/src/main/java"
rm -rf "$out/dev/mikuteto/mtlrender/ffi"
includes=$(grep -oE 'mtlr_[a-z_]+\(' "$root/abi/mtlrender.h" | tr -d '(' | sed 's/^/--include-function /' | tr '\n' ' ')
includes="$includes --include-struct MtlrCaps --include-struct MtlrGlslSource --include-struct MtlrGlslDefine --include-struct MtlrGlslOptions --include-struct MtlrEvalFrame --include-struct MtlrMesherSection --include-struct MtlrFrameState --include-struct MtlrLodMeshResult --include-struct MtlrCalibration"
"${JEXTRACT:-$HOME/.local/opt/jextract-25/bin/jextract}" --target-package dev.mikuteto.mtlrender.ffi --header-class-name Mtlr --output "$out" $includes "$root/abi/mtlrender.h"
