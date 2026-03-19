#!/usr/bin/env bash
set -euo pipefail

components=(
  "core-contracts"
  "f1-provisioning"
  "f2-camera-ocr"
  "f3-text-postprocess"
  "f4-dictionary"
  "f5-tts"
  "f6-performance"
)

layout_file="gradle/repo-layout.properties"

resolve_component_prefix() {
  local name="$1"
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

for name in "${components[@]}"; do
  prefix="$(resolve_component_prefix "$name")"
  echo "[subtree] pulling $name"
  git fetch "$name" main
  git subtree pull --prefix="$prefix" "$name" main
done

echo "[subtree] all components updated"
