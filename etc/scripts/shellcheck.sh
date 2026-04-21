#!/bin/bash -e
#
# Copyright (c) 2024, 2026 Oracle and/or its affiliates.
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

# Path to this script
if [ -h "${0}" ] ; then
  SCRIPT_PATH="$(readlink "${0}")"
else
  SCRIPT_PATH="${0}"
fi
readonly SCRIPT_PATH

# Path to the root of the workspace
WS_DIR=$(cd "$(dirname -- "${SCRIPT_PATH}")" && cd ../.. && pwd -P)
readonly WS_DIR

BASE_URL="https://github.com/koalaman/shellcheck/releases/download"
readonly BASE_URL

VERSION=0.11.0
readonly VERSION

CACHE_DIR="${HOME}/.shellcheck"
readonly CACHE_DIR

# Caching the shellcheck
mkdir -p "${CACHE_DIR}"
if [ ! -e "${CACHE_DIR}/${VERSION}/shellcheck" ] ; then
  ARCH=$(uname -m | tr "[:upper:]" "[:lower:]")
  PLATFORM=$(uname -s | tr "[:upper:]" "[:lower:]")
  # if using Mac with a silicon chip, use aarch64 as the architecture
  if [[ "${PLATFORM}" == "darwin" && "${ARCH}" == "arm64" ]]; then
    ARCH=aarch64
  fi
  curl -Lso "${CACHE_DIR}/sc.tar.xz" "${BASE_URL}/v${VERSION}/shellcheck-v${VERSION}.${PLATFORM}.${ARCH}.tar.xz"
  tar -xf "${CACHE_DIR}/sc.tar.xz" -C "${CACHE_DIR}"
  mkdir "${CACHE_DIR}/${VERSION}"
  mv "${CACHE_DIR}/shellcheck-v${VERSION}/shellcheck" "${CACHE_DIR}/${VERSION}/shellcheck"
  rm -rf "${CACHE_DIR}/shellcheck-v${VERSION}" "${CACHE_DIR}/sc.tar.xz"
fi
export PATH="${CACHE_DIR}/${VERSION}:${PATH}"

echo "ShellCheck version"
shellcheck --version

status_code=0
while IFS= read -r -d '' file; do
  printf "\n-- Checking file:  %s --\n" "${file}"
  if ! shellcheck "${WS_DIR}/${file}" ; then
    status_code=${?}
  fi
done < <(git -C "${WS_DIR}" ls-files -z '*.sh')

exit "${status_code}"
