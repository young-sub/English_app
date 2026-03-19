#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <component-name>"
  exit 1
fi

name="$1"
layout_file="gradle/repo-layout.properties"

resolve_component_prefix() {
  local key="module.$name="

  if [[ -f "$layout_file" ]]; then
    while IFS= read -r line; do
      if [[ "$line" == "$key"* ]]; then
        printf '%s\n' "${line#*=}"
        return 0
      fi
    done < "$layout_file"
  fi

  printf 'components/%s\n' "$name"
}

prefix="$(resolve_component_prefix)"

git subtree push --prefix="$prefix" "$name" main

echo "[subtree] pushed $name from $prefix"
