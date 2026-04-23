#!/usr/bin/env python3
import argparse
import base64
import json
import sys

from cryptography.hazmat.primitives import serialization

try:
    from cryptography.hazmat.primitives.asymmetric import ed25519
except Exception as exc:  # pragma: no cover
    print(f"Failed to import cryptography primitives: {exc}", file=sys.stderr)
    sys.exit(2)


def build_payload(data: dict) -> str:
    return "\n".join(
        [
            f"versionCode={data['versionCode']}",
            f"versionName={data['versionName']}",
            f"apkUrl={data['apkUrl']}",
            f"sha256={str(data['sha256']).lower()}",
            f"notesRu={data.get('notesRu', '')}",
            f"notesEn={data.get('notesEn', '')}",
            f"publishedAt={data.get('publishedAt', '')}",
            f"minSupportedVersionCode={data['minSupportedVersionCode']}",
            f"minSdk={data['minSdk']}",
        ]
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Sign update.json with Ed25519 private key")
    parser.add_argument("--input", required=True, help="Path to update.json file")
    parser.add_argument("--private-key", required=True, help="Path to Ed25519 private PEM key")
    args = parser.parse_args()

    with open(args.input, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Ensure signature is not included in signed payload
    data.pop("signature", None)
    payload = build_payload(data).encode("utf-8")

    with open(args.private_key, "rb") as f:
        private_key = serialization.load_pem_private_key(f.read(), password=None)

    if not isinstance(private_key, ed25519.Ed25519PrivateKey):
        print("Private key is not Ed25519", file=sys.stderr)
        return 1

    signature = private_key.sign(payload)
    data["signature"] = base64.b64encode(signature).decode("ascii")

    with open(args.input, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
