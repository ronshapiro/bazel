#!/bin/bash
#
# Copyright 2018 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

gold_file=$1
shift

if [ -d "$TEST_TMPDIR" ]; then
  # Running as part of blaze test
  tmpdir="$TEST_TMPDIR"
else
  # Manual run from command line
  tmpdir="$(mktemp -d)"
fi

if [ -d "$TEST_UNDECLARED_OUTPUTS_DIR" ]; then
  # Running as part of blaze test: capture test output
  output="$TEST_UNDECLARED_OUTPUTS_DIR"
else
  # Manual run from command line: just write into temp dir
  output="${tmpdir}"
fi

actual_file="${output}/actual_result.txt"
# Run the checker command.
$@ --output "${actual_file}" &> ${output}/checker_output.txt

checker_ret=$?
if [[ "${checker_ret}"  != 0 ]] ; then
  echo "Checker error!!! ${checker_ret}"
  cat ${output}/checker_output.txt
  exit ${checker_ret}
fi

diff "${gold_file}" "${actual_file}"

ret=$?
if [[ "${ret}" != 0 ]] ; then
  echo "============== Actual Output =============="
  cat "${actual_file}"
  echo "" # New line.
  echo "==========================================="
fi
exit ${ret}
