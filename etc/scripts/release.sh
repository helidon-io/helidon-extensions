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
readonly MAVEN_REPO_URL="${MAVEN_REPO_URL:-https://repo1.maven.org/maven2}"

usage(){
    cat <<EOF

DESCRIPTION: Helidon Release Script

USAGE:

$(basename "${0}") update_version --extension=ID --version=V
$(basename "${0}") create_tag

  --version=V
        The version to use.

  --extension=ID
        The extension id to use.

  --help
        Prints the usage and exits.

  CMD:

    update_version
        Update the version in the workspace

    create_tag
        Create and and push a release tag
EOF
}

# parse command line args
ARGS=( )
while (( ${#} > 0 )); do
  case ${1} in
  "--version="*)
    VERSION=${1#*=}
    shift
    ;;
  "--extension="*)
    EXTENSION_ID=${1#*=}
    shift
    ;;
  "--help")
    usage
    exit 0
    ;;
  "update_version"|"create_tag")
    COMMAND="${1}"
    shift
    ;;
  *)
    ARGS+=( "${1}" )
    shift
    ;;
  esac
done
readonly ARGS
readonly COMMAND

# copy stdout as fd 6 and redirect stdout to stderr
# this allows us to use fd 6 for returning data
exec 6>&1 1>&2

case ${COMMAND} in
"update_version")
  if [ -z "${VERSION}" ] ; then
    echo "ERROR: version required" >&2
    usage
    exit 1
  elif [ -z "${EXTENSION_ID:-}" ] ; then
    echo "ERROR: extension required" >&2
    usage
    exit 1
  elif [ ! -f "${WS_DIR}/extensions/${EXTENSION_ID}/pom.xml" ] ; then
    echo "ERROR: unknown extension id ${EXTENSION_ID}" >&2
    exit 1
  fi
  ;;
"create_tag")
  # no-op
  ;;
"")
  echo "ERROR: no command provided" >&2
  usage
  exit 1
  ;;
*)
  echo "ERROR: unknown command ${COMMAND}" >&2
  usage
  exit 1
  ;;
esac

# arg1: pom file
gav() {
  awk '
    BEGIN {
      FS="[<>]"; in_parent=0
    }
    /<parent>/ {in_parent=1; next}
    in_parent && /<groupId>/ {parent_group_id=$3}
    in_parent && /<version>/ {parent_version=$3}
    /<\/parent>/ {in_parent=0; next}
    /<project[ >]/ || /<modelVersion>/ {next}
    !in_parent && /<groupId>/ {group_id=$3}
    !in_parent && /<artifactId>/ {artifact_id=$3}
    !in_parent && /<version>/ {version=$3}
    !in_parent && /<[[:alpha:]][^>]*>/ && $2 !~ /^(groupId|artifactId|version)$/ { exit }
    END {
      if (group_id == "") {
        group_id=parent_group_id
      }
      if (version == "") {
        version=parent_version
      }
      if (group_id == "" || artifact_id == "" || version == "") {
        exit 1
      }
      print group_id, artifact_id, version
    }
  ' "${1}"
}

# arg1: pom file
parent_gav() {
  awk -F'[<>]' '
    /<parent>/ { in_parent = 1 }
    /<\/parent>/ {
      print group_id, artifact_id, version
      exit
    }
    in_parent && $2 == "groupId"    { group_id = $3 }
    in_parent && $2 == "artifactId" { artifact_id = $3 }
    in_parent && $2 == "version"    { version = $3 }
  ' "${1}"
}

resolve_extension() {
  local branch_name extension
  branch_name="$(git -C "${WS_DIR}" branch --show-current)"
  if [[ "${branch_name}" == */release-* ]] ; then
    extension="${branch_name%%/*}"
    if [ -f "${WS_DIR}/extensions/${extension}/pom.xml" ] ; then
      echo "${extension}"
    else
      echo "ERROR: unknown extension ${extension}" >&2
      exit 1
    fi
  else
    echo "ERROR: release branch must match <extension>/release-* (found ${branch_name})" >&2
    exit 1
  fi
}

# arg1: url
download() {
  local status file
  file=$(mktemp)

  if ! status=$(curl -sSL --retry 3 --write-out "%{http_code}" --output "${file}" "${1}") ; then
    status=-1
  fi
  echo "${status} ${file}"
}

# arg1: group_id
# arg2: artifact_id
resolve_latest_version() {
  # download maven-metadata.xml
  read -r status file < <(download "${MAVEN_REPO_URL%/}/${1//.//}/${2}/maven-metadata.xml")
  if [ "${status}" = "200" ] ; then
    # parse and resolve the latest version
    awk '
      BEGIN {FS="[<>]"}
      /<release>/ && $3 !~ /-SNAPSHOT$/ {release=$3}
      /<version>/ && $3 !~ /-SNAPSHOT$/ {version=$3}
      END {
        if (release != "") {
          print release
        } else if (version != "") {
          print version
        }
      }
    ' "${file}"
  fi
}

# arg1: group_id
# arg2: artifact_id
# arg3: version
remote_md5() {
  read -r status file < <(download "${MAVEN_REPO_URL%/}/${1//.//}/${2}/${3}/${2}-${3}.pom.md5")
  if [ "${status}" = "200" ] ; then
    awk '{print $1}' "${file}" | tr '[:upper:]' '[:lower:]'
  fi
}

# arg1: file
checksum() {
  if command -v md5sum > /dev/null 2>&1 ; then
    md5sum "${1}" | awk '{print $1}' | tr '[:upper:]' '[:lower:]'
  else
    md5 -q "${1}" | tr '[:upper:]' '[:lower:]'
  fi
}

# arg1: pom file
# arg2: version
set_project_version() {
  echo "Updating version ${1}" >&2
  awk -F'[<>]' -v version="${2}" '
    function name(s) {
      sub(/^[[:space:]]*\//, "", s)
      sub(/[[:space:]\/].*/, "", s)
      return s
    }
    depth == 1 && name($2) == "version" {
      sub(/>[^<]+</, ">" version "<")
    }
    { print }
    {
      for (i = 2; i <= NF; i += 2) {
        tag = $i
        sub(/^[[:space:]]+|[[:space:]]+$/, "", tag)
        if (tag == "" || tag ~ /^(!--|\?|!)/) continue
        if (tag ~ /^\//) depth--
        else if (tag !~ /\/$/) depth++
      }
    }
  ' "${1}" > "${1}.tmp"
   mv "${1}.tmp" "${1}"
}

# arg1: pom file
# arg3: version
set_parent_version(){
  echo "Updating parent ${1}" >&2
  awk -F'[<>]' -v version="$version" '
    /<parent>/ { in_parent = 1 }
    in_parent && $2 == "version" {
      sub(/>[^<]+</, ">" version "<")
      in_parent = 0
    }
    { print }
    ' "${1}" > "${1}.tmp"
  mv "${1}.tmp" "${1}"
}

# arg1: pom file
resolve_pom_version() {
  local changed latest_version version pom tmp
  pom="${1}"

  # read pom coordinates
  read -r group_id artifact_id current_version < <(gav "${WS_DIR}/${pom}")

  # parse and resolve the latest version
  latest_version=$(resolve_latest_version "${group_id}" "${artifact_id}")
  if [ -n "${latest_version}" ] ; then
    tmp=$(mktemp)

    # make a temp copy of the pom with updated project version
    cp "${WS_DIR}/${pom}" "${tmp}"
    set_project_version "${tmp}" "${latest_version}"

    # compare the checksums
    if [ "$(checksum "${tmp}")" = "$(remote_md5 "${group_id}" "${artifact_id}" "${current_version}")" ] ; then
      version="${latest_version}"
      changed="false"
    else
      version="${latest_version%%-*}"
      version="${version%.*}.$((${version##*.}+1))"
      changed="true"
    fi
  else
    version="${current_version%%-*}"
    changed="true"
  fi

  echo "${version} ${changed}"
}

# arg1: extension_id
update_parents() {
  local poms extension
  extension="${1}"

  # update parent and project poms
  for i in "parent/pom.xml" "pom.xml"; do
    read -r _ artifact_id _ < <(gav "${WS_DIR}/${i}")
    echo "Processing parent ${artifact_id}" >&2

    # resolve the version
    read -r version changed < <(resolve_pom_version "${i}")

    # update the version
    set_project_version "${WS_DIR}/${i}" "${version}"

    # add the pom to the result list
    if [ "${changed}" = "true" ] ; then
      if [ -n "${poms}" ] ; then
        poms="${poms},${i}"
      else
        poms="${i}"
      fi
    fi

    # update parent references
    while read -r pom; do
      if [ -n "${pom}" ] ; then
        read -r _ parent_artifact_id _ < <(parent_gav "${WS_DIR}/${pom}")
        if [ "${parent_artifact_id}" = "${artifact_id}" ] ; then
          set_parent_version "${WS_DIR}/${pom}" "${version}"
        fi
      fi
    done < <(git -C "${WS_DIR}" ls-files \
      "pom.xml" \
      "extension/pom.xml" \
      "extensions/${extension}/pom.xml" \
      "extensions/${extension}/*/pom.xml")
  done

  # return the poms to be released
  echo "${poms}"
}

# arg1: version (optional)
# arg2: extension (optional)
update_version(){
  local extension extension_path version

  version=${1-${VERSION}}
  extension="${2-${EXTENSION_ID:-}}"
  extension_path="${WS_DIR}/extensions/${extension}"

  # Read extension group_id
  read -r extension_group_id _ < <(gav "${extension_path}/pom.xml")

  # update poms
  while read -r pom; do
    if [ -n "${pom}" ] ; then
      # Update project version
      set_project_version "${WS_DIR}/${pom}" "${version}"

      # Update parent version
      read -r parent_group_id _ _ < <(parent_gav "${WS_DIR}/${pom}")
      if [[ "${parent_group_id}" == ${extension_group_id}* ]] ; then
        set_parent_version "${WS_DIR}/${pom}" "${version}"
      fi
    fi
  done < <(git -C "${WS_DIR}" ls-files \
    "extensions/${extension}/pom.xml" \
    "extensions/${extension}/*/pom.xml")
}

create_tag() {
  local extension git_branch poms tag_name

  # Resolve extension from the current branch name
  extension="$(resolve_extension)"

  # Read version from extension pom
  read -r _ _ version < <(gav "${WS_DIR}/extensions/${extension}/pom.xml")

  # Strip qualifier
  version="${version%%-*}"

  git_branch="release/${extension}/${version}"
  tag_name="${extension}/${version}"

  # Use a separate branch
  git branch -D "${git_branch}" > /dev/null 2>&1 || true
  git checkout -b "${git_branch}"

  # Update parent poms
  poms=$(update_parents "${extension}")

  # Invoke update_version
  update_version "${version}" "${extension}"

  # Git user info
  git config user.email || git config --global user.email "info@helidon.io"
  git config user.name || git config --global user.name "Helidon Robot"

  # Commit version changes
  git commit -a -m "Release ${extension} ${version}"

  # Create and push a git tag
  git tag -f "${tag_name}"
  git push --force origin refs/tags/"${tag_name}":refs/tags/"${tag_name}"

  # GitHub action outputs
  echo "version=${version}" >&6
  echo "tag=${tag_name}" >&6
  echo "extension=${extension}" >&6
  echo "poms=${poms}" >&6
}

# Invoke command
${COMMAND}
