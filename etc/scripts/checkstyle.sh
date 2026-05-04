#!/bin/bash
#
# Copyright (c) 2018, 2026 Oracle and/or its affiliates.
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
set -o errtrace || true # trace ERR through commands and functions
set -o errexit || true  # exit the script if any statement returns a non-true return value

on_error() {
  CODE="${?}" && \
  set +x && \
  printf "[ERROR] Error(code=%s) occurred at %s:%s command: %s\n" \
    "${CODE}" "${BASH_SOURCE[0]}" "${LINENO}" "${BASH_COMMAND}"
}
trap on_error ERR

die() { echo "${1}" >&2 ; exit 1 ;}

download_checkstyle() {
  local download_url
  local tmp_file

  mkdir -p "${JAR_DIR}"
  if [ ! -e "${JAR_FILE}" ] ; then
    download_url="${BASE_URL}/checkstyle-${VERSION}/checkstyle-${VERSION}-all.jar"
    tmp_file="${JAR_FILE}.tmp.$$"
    rm -f "${tmp_file}"
    if ! curl -fLso "${tmp_file}" "${download_url}" ; then
      rm -f "${tmp_file}"
      exit 1
    fi
    mv "${tmp_file}" "${JAR_FILE}"
  fi
}

is_main_java_file() {
  local file="$1"

  case "${file}" in
  src/test/java/*|*/src/test/java/*)
    return 1
    ;;
  tests/*|*/tests/*)
    return 1
    ;;
  examples/*|*/examples/*)
    return 1
    ;;
  src/it/*|*/src/it/*)
    return 1
    ;;
  src/main/java/*.java|*/src/main/java/*.java)
    return 0
    ;;
  *)
    return 1
    ;;
  esac
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
# shellcheck disable=SC2046
WS_DIR=$(cd $(dirname -- "${SCRIPT_PATH}") ; cd ../.. ; pwd -P)
readonly WS_DIR

LOG_FILE=$(mktemp -t XXXcheckstyle-log)
readonly LOG_FILE

RESULT_FILE=$(mktemp -t XXXcheckstyle-result)
readonly RESULT_FILE

BASE_URL="https://github.com/checkstyle/checkstyle/releases/download"
readonly BASE_URL

VERSION=13.3.0
readonly VERSION

CACHE_DIR="${HOME}/.checkstyle"
readonly CACHE_DIR

JAR_DIR="${CACHE_DIR}/${VERSION}"
readonly JAR_DIR

JAR_FILE="${JAR_DIR}/checkstyle-${VERSION}-all.jar"
readonly JAR_FILE

download_checkstyle

echo "Checkstyle version"
java -jar "${JAR_FILE}" -V

JAVA_FILES=()
while IFS= read -r -d '' file; do
  if is_main_java_file "${file}" ; then
    JAVA_FILES+=("${file}")
  fi
done < <(git -C "${WS_DIR}" ls-files -z '*.java')

if [ "${#JAVA_FILES[@]}" -eq 0 ] ; then
  : > "${LOG_FILE}"
  : > "${RESULT_FILE}"
  echo "CHECKSTYLE OK"
  exit 0
fi

cd "${WS_DIR}"
status_code=0
if java -jar "${JAR_FILE}" \
  -c "${WS_DIR}/etc/checkstyle.xml" \
  -f plain \
  -o "${RESULT_FILE}" \
  "${JAVA_FILES[@]}" \
  > "${LOG_FILE}" 2>&1 ; then
  status_code=0
else
  status_code=${?}
fi

if [ "${status_code}" -ne 0 ] ; then
  cat "${LOG_FILE}"
  exit 1
fi

grep "^\[ERROR\]" "${RESULT_FILE}" \
    && die "CHECKSTYLE ERROR" || echo "CHECKSTYLE OK"
