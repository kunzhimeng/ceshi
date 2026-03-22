#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 2 ]; then
  echo "usage: jni_symbol_diff.sh <old-libnative.so> <new-libnative.so>"
  exit 1
fi
for f in "$1" "$2"; do
  echo "### $f"
  strings -n 8 "$f" | grep 'Java_com_ss_android_ugc_awemes_ModuleMain_' | sort -u || true
  echo
 done
