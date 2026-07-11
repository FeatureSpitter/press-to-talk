#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
exec .venv/bin/python3 press_to_talk.py "$@"
