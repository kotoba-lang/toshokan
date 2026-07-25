#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compiler_dir=${KOTOBA_COMPILER_DIR:-"$repo_dir/../compiler"}
output_dir="$repo_dir/workerd/generated"

mkdir -p "$output_dir"
cd "$compiler_dir"
clojure -M:run compile \
  "$repo_dir/src/toshokan/portable_effect.kotoba" \
  --target javascript \
  --output "$output_dir/portable-effect.mjs"
