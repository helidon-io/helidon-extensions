#!/bin/bash -e
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

WS_DIR=$(cd "$(dirname -- "${0}")/../.." && pwd -P)
readonly WS_DIR

# Release tags can contain different versions of the two connectors.
version() {
    awk -F '[<>]' '
        /<parent>/,/<\/parent>/ {next}
        /<version>/ {print $3; found = 1; exit}
        END {if (!found) exit 1}
    ' "${1}"
}

JMS_VERSION=$(version "${WS_DIR}/extensions/messaging/jms/pom.xml")
JMS_JAVAX_VERSION=$(version "${WS_DIR}/extensions/messaging/jms-javax/pom.xml")
readonly JMS_VERSION JMS_JAVAX_VERSION

# shellcheck disable=SC2086
mvn ${MVN_ARGS:-} \
    -f "${WS_DIR}/extensions/messaging/pom.xml" \
    -Ptests -pl tests/jms-interop -am \
    -Dmessaging.jms.extension.version="${JMS_VERSION}" \
    -Dmessaging.jms-javax.extension.version="${JMS_JAVAX_VERSION}" \
    "${@}" verify
