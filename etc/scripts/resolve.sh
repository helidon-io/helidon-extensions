#!/bin/bash
#
# Copyright (c) 2026 Oracle and/or its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -o pipefail || true  # trace ERR through pipes
set -o errtrace || true  # trace ERR through commands and functions
set -o errexit || true  # exit the script if any statement returns a non-true return value

on_error() {
  CODE="${?}" && \
  set +x && \
  printf "[ERROR] Error(code=%s) occurred at %s:%s command: %s\n" \
    "${CODE}" "${BASH_SOURCE[0]}" "${LINENO}" "${BASH_COMMAND}" >&2
}
trap on_error ERR

die() { echo "${1}" >&2 ; exit 1 ;}

print_usage() {
  cat <<EOF

DESCRIPTION: Resolve Extension and Helidon Version Sets

USAGE:

$(basename "${0}")
$(basename "${0}") --branch-name NAME
$(basename "${0}") --base-sha SHA --source-sha SHA

With no arguments, all extensions are selected.
When both SHAs are non-empty, PR diff selection takes precedence over
branch-name selection.

OPTIONS:

  --branch-name NAME
        Resolve extensions from one branch name. Branches matching
        <extension>/... select exactly one known extension, otherwise all
        extensions are selected.

  --base-sha SHA
        Base revision for pull-request style selection.

  --source-sha SHA
        Source revision for pull-request style selection.

  --help
        Prints the usage and exits.
EOF
}

usage() {
  print_usage >&2
  exit 1
}

help() {
  print_usage
  exit 0
}

usage_error() {
  [ -z "${1:-}" ] || echo "${1}" >&2
  usage
}

# Path to this script
if [ -h "${0}" ] ; then
  SCRIPT_PATH="$(readlink "${0}")"
else
  # shellcheck disable=SC155
  SCRIPT_PATH="${0}"
fi
readonly SCRIPT_PATH

# Path to the root of the workspace
WS_DIR=$(cd "$(dirname -- "${SCRIPT_PATH}")" && cd ../.. && pwd -P)
readonly WS_DIR

json_escape() {
  local value

  value="${1}"

  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s' "${value}"
}

version() {
  awk 'BEGIN {FS="[<>]"} ; /<helidon.version>/ {print $3; exit 0}' "${1}"
}

contains_value() {
  local candidate ext_id

  ext_id="${1}"
  shift
  for candidate in "${@}"; do
    if [ "${candidate}" = "${ext_id}" ] ; then
      return 0
    fi
  done
  return 1
}

EXTENSION_IDS=()
EXTENSION_HELIDON_VERSIONS=()
SELECTED_INDEXES=()

find_extension_index() {
  local ext_id index

  ext_id="${1}"
  for index in "${!EXTENSION_IDS[@]}"; do
    if [ "${EXTENSION_IDS[${index}]}" = "${ext_id}" ] ; then
      printf '%s' "${index}"
      return 0
    fi
  done
  return 1
}

discover_extensions() {
  local entries ext_id helidon_version pom

  entries=$(for pom in "${WS_DIR}"/extensions/*/pom.xml; do
    if [ -f "${pom}" ] ; then
      ext_id="${pom#"${WS_DIR}"/}"
      ext_id="${ext_id%/pom.xml}"
      ext_id="${ext_id#extensions/}"
      printf '%s|%s\n' "${ext_id}" "${pom}"
    fi
  done | LC_ALL=C sort)

  if [ -z "${entries}" ] ; then
    echo "No extension pom.xml files found under ${WS_DIR}/extensions/*/pom.xml." >&2
    exit 1
  fi

  while IFS='|' read -r ext_id pom; do
    if [ -n "${ext_id}" ] ; then
      helidon_version=$(version "${pom}")
      if [ -z "${helidon_version}" ] ; then
        die "Missing <helidon.version> in ${pom}."
      fi
      EXTENSION_IDS+=("${ext_id}")
      EXTENSION_HELIDON_VERSIONS+=("${helidon_version}")
    fi
  done <<< "${entries}"
}

select_all_extensions() {
  local index

  SELECTED_INDEXES=()
  for index in "${!EXTENSION_IDS[@]}"; do
    SELECTED_INDEXES+=("${index}")
  done
}

select_extensions_by_ids() {
  local index requested_ids

  requested_ids=("${@}")
  SELECTED_INDEXES=()
  for index in "${!EXTENSION_IDS[@]}"; do
    if contains_value "${EXTENSION_IDS[${index}]}" "${requested_ids[@]}" ; then
      SELECTED_INDEXES+=("${index}")
    fi
  done
}

select_pull_request_extensions() {
  local base_sha changed_files ext_id path requested_ids source_sha

  base_sha="${1}"
  source_sha="${2}"
  requested_ids=()
  changed_files=$(git -C "${WS_DIR}" diff --name-only "${base_sha}" "${source_sha}")

  if [ -z "${changed_files}" ] ; then
    select_all_extensions
    return 0
  fi

  while IFS= read -r path; do
    if [ -n "${path}" ] ; then
      case "${path}" in
      extensions/*/*)
        ext_id="${path#extensions/}"
        ext_id="${ext_id%%/*}"
        if find_extension_index "${ext_id}" > /dev/null ; then
          if ! contains_value "${ext_id}" "${requested_ids[@]}" ; then
            requested_ids+=("${ext_id}")
          fi
        else
          select_all_extensions
          return 0
        fi
        ;;
      *)
        select_all_extensions
        return 0
        ;;
      esac
    fi
  done <<< "${changed_files}"

  if [ "${#requested_ids[@]}" -eq 0 ] ; then
    select_all_extensions
    return 0
  fi

  select_extensions_by_ids "${requested_ids[@]}"
}

select_branch_extensions() {
  local branch_name ext_id

  branch_name="${1}"
  case "${branch_name}" in
  */*)
    ext_id="${branch_name%%/*}"
    if find_extension_index "${ext_id}" > /dev/null ; then
      select_extensions_by_ids "${ext_id}"
      return 0
    fi
    ;;
  esac
  select_all_extensions
}

extensions_json() {
  local ext_id index json separator

  json='['
  separator=""
  for index in "${SELECTED_INDEXES[@]}"; do
    ext_id="${EXTENSION_IDS[${index}]}"
    json="${json}${separator}{\"id\":\"$(json_escape "${ext_id}")\""
    json="${json},\"helidon_version\":\"$(json_escape "${EXTENSION_HELIDON_VERSIONS[${index}]}")\"}"
    separator=","
  done
  json="${json}]"
  printf '%s' "${json}"
}

versions_json() {
  local helidon_version json separator versions

  versions=$(for helidon_version in "${SELECTED_INDEXES[@]}"; do
    printf '%s\n' "${EXTENSION_HELIDON_VERSIONS[${helidon_version}]}"
  done | LC_ALL=C sort -u)

  json='['
  separator=""
  while IFS= read -r helidon_version; do
    if [ -n "${helidon_version}" ] ; then
      json="${json}${separator}\"$(json_escape "${helidon_version}")\""
      separator=","
    fi
  done <<< "${versions}"
  json="${json}]"
  printf '%s' "${json}"
}

BRANCH_NAME=""
BASE_SHA=""
SOURCE_SHA=""

while [ "${#}" -gt 0 ]; do
  case "${1}" in
  --help)
    help
    ;;
  --branch-name)
    shift
    if [ "${#}" -gt 0 ] ; then
      BRANCH_NAME="${1}"
    else
      usage_error "Missing value for --branch-name."
    fi
    shift
    ;;
  --base-sha)
    shift
    if [ "${#}" -gt 0 ] ; then
      BASE_SHA="${1}"
    else
      usage_error "Missing value for --base-sha."
    fi
    shift
    ;;
  --source-sha)
    shift
    if [ "${#}" -gt 0 ] ; then
      SOURCE_SHA="${1}"
    else
      usage_error "Missing value for --source-sha."
    fi
    shift
    ;;
  *)
    usage_error "Unknown option: ${1}"
    ;;
  esac
done

if [ -n "${BASE_SHA}" ] || [ -n "${SOURCE_SHA}" ] ; then
  if [ -z "${BASE_SHA}" ] ; then
    usage_error "--source-sha requires --base-sha."
  elif [ -z "${SOURCE_SHA}" ] ; then
    usage_error "--base-sha requires --source-sha."
  fi
fi

discover_extensions

if [ -n "${BASE_SHA}" ] ; then
  select_pull_request_extensions "${BASE_SHA}" "${SOURCE_SHA}"
elif [ -n "${BRANCH_NAME}" ] ; then
  select_branch_extensions "${BRANCH_NAME}"
else
  select_all_extensions
fi

printf 'extensions=%s\n' "$(extensions_json)"
printf 'versions=%s\n' "$(versions_json)"
