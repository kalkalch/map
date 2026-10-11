#!/usr/bin/env python3
import argparse
import base64
import os
import sys


def decode_candidates(raw: str):
    yield raw
    yield raw.replace("\\n", "\n")
    try:
        decoded = base64.b64decode(raw, validate=True).decode("utf-8")
        yield decoded
    except Exception:
        return


def main() -> int:
    parser = argparse.ArgumentParser(description="Normalize update signing PEM secret.")
    parser.add_argument("--secret-env", default="MAP_UPDATE_SIGNING_PRIVATE_PEM")
    parser.add_argument("--output", default="update_signing_private.pem")
    args = parser.parse_args()

    raw = os.environ.get(args.secret_env, "").strip()
    if not raw:
        print(f"{args.secret_env} is empty", file=sys.stderr)
        return 1

    pem = None
    for candidate in decode_candidates(raw):
        if "BEGIN PRIVATE KEY" in candidate and "END PRIVATE KEY" in candidate:
            pem = candidate.strip() + "\n"
            break

    if pem is None:
        print(
            f"{args.secret_env} must be raw PEM, escaped PEM (with \\n), or base64 PEM",
            file=sys.stderr,
        )
        return 1

    with open(args.output, "w", encoding="utf-8") as f:
        f.write(pem)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
