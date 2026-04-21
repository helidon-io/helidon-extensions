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

set -x

set -o pipefail || true  # trace ERR through pipes
set -o errtrace || true # trace ERR through commands and functions
set -o errexit || true  # exit the script if any statement returns a non-true return value

on_error(){
  CODE="${?}" && \
  set +x && \
  printf "[ERROR] Error(code=%s) occurred at %s:%s command: %s\n" \
    "${CODE}" "${BASH_SOURCE[0]}" "${LINENO}" "${BASH_COMMAND}" >&2
}
trap on_error ERR

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

print_usage() {
    cat <<EOF

DESCRIPTION: Prime Helidon Build Cache Script

USAGE:

$(basename "${0}") --version HELIDON_VERSION

  --version HELIDON_VERSION
        Run the priming build for one Helidon version such as
        27.0.0-SNAPSHOT.

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

version() {
    awk 'BEGIN {FS="[<>]"} ; /<helidon.version>/ {print $3; exit 0}' "${1}"
}

branch() {
  local version branch
  version="${1}"

  branch="helidon-${version%%.*}.x"
  if [ -z "$(git ls-remote --heads https://github.com/helidon-io/helidon "refs/heads/${HELIDON_BRANCH}")" ] ; then
    branch="main"
  fi
  echo "${branch}"
}

VERSION_INPUT=""
while [ "${#}" -gt 0 ]; do
  case "${1}" in
  --help)
    help
    ;;
  --version)
    if [ -n "${VERSION_INPUT}" ] ; then
      usage_error "Only one --version <version> is supported."
    fi
    shift
    if [ "${#}" -eq 0 ] ; then
      usage_error "Missing version for --version."
    fi
    VERSION_INPUT="${1}"
    shift
    ;;
  *)
    usage_error "Unknown option: ${1}"
    ;;
  esac
done

if [ -z "${VERSION_INPUT}" ] ; then
    usage
fi

HELIDON_VERSION="${VERSION_INPUT}"
readonly HELIDON_VERSION
echo "HELIDON_VERSION=${HELIDON_VERSION}"

# add a marker file for the build cache
mkdir -p "${WS_DIR}/.m2/repository/io/helidon/.primed"
printf '%s\n' "${HELIDON_VERSION}" > "${WS_DIR}/.m2/repository/io/helidon/.primed/${HELIDON_VERSION}"

if [[ ! ${HELIDON_VERSION} == *-SNAPSHOT ]]; then
  echo "Helidon version ${HELIDON_VERSION} is not a SNAPSHOT version. Skipping priming build."
  exit 0
fi

HELIDON_BRANCH=$(branch "${HELIDON_VERSION}")
readonly HELIDON_BRANCH

cd "$(mktemp -d)"
git clone https://github.com/helidon-io/helidon --branch "${HELIDON_BRANCH}" --single-branch --depth 1

HELIDON_VERSION_IN_REPO=$(version helidon/bom/pom.xml)
readonly HELIDON_VERSION_IN_REPO

if [ "${HELIDON_VERSION}" != "${HELIDON_VERSION_IN_REPO}" ]; then
  echo "ERROR: Examples Helidon version ${HELIDON_VERSION} does not match version in Helidon repo ${HELIDON_VERSION_IN_REPO}"
  exit 1
fi

# shellcheck disable=SC2086
mvn ${MVN_ARGS} --version

echo "Building Helidon version ${HELIDON_VERSION} from Helidon repo branch ${HELIDON_BRANCH}"

# shellcheck disable=SC2086
mvn ${MVN_ARGS} -T8 \
  -f helidon/pom.xml \
  -DskipTests \
  -Dmaven.test.skip=true \
  install
