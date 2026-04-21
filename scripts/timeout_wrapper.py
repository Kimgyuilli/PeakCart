#!/usr/bin/env python3
"""
GNU timeout(1) equivalent for macOS (no coreutils required).

Usage:
    python3 scripts/timeout_wrapper.py <seconds> <command> [args...]

Exits with 124 on timeout (GNU timeout convention), or the child's exit code.
SIGTERM is sent first; after a 2s grace period, SIGKILL is sent.
"""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import time


GRACE_SECONDS = 2
TIMEOUT_EXIT_CODE = 124


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: timeout_wrapper.py <seconds> <command> [args...]", file=sys.stderr)
        return 2

    try:
        seconds = float(argv[1])
    except ValueError:
        print(f"invalid seconds: {argv[1]}", file=sys.stderr)
        return 2

    cmd = argv[2:]
    proc = subprocess.Popen(cmd, start_new_session=True)

    try:
        return proc.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        pass

    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return TIMEOUT_EXIT_CODE

    grace_end = time.monotonic() + GRACE_SECONDS
    while time.monotonic() < grace_end:
        if proc.poll() is not None:
            return TIMEOUT_EXIT_CODE
        time.sleep(0.1)

    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    proc.wait()
    return TIMEOUT_EXIT_CODE


if __name__ == "__main__":
    sys.exit(main(sys.argv))
