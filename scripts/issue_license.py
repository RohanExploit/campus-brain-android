"""
Issue a Campus Brain licence key, offline.

Signs a licence payload with a P-256 private key and prints the typeable
`CBI-…` string to paste into an email. Nothing here talks to a network, a
database or the app; the only input that matters is the private key file, and
the only output is a string.

This script is NOT part of the Android build. It lives in scripts/, is never
copied into app/, and the APK contains only the matching PUBLIC key -- see the
header of
app/src/main/java/com/kriet/campusbrain/data/auth/License.kt for why the scheme
is asymmetric at all. Short version: `isMinifyEnabled = false`, so the APK
decompiles cleanly, and an embedded HMAC secret would let the first person to
unzip it mint unlimited institutional keys.

Fail-closed, in the style of auth/api_keys.py: a missing or unreadable key file
is an error and not a fallback, an out-of-range flag is refused rather than
clamped, and nothing is ever printed on a path that did not fully validate. The
difference from that module is the direction of the failure -- api_keys.py
degrades to "no extra keys" because it is deciding whether to GRANT access at
runtime, and this script exits non-zero because it is deciding whether to MINT
a credential, where the safe default is to produce nothing.

The private key is never printed, never logged, and never written anywhere by
this script.

Usage
-----

    # once, to create the founder keypair (keep the private key off the repo)
    python scripts/issue_license.py --generate-keypair --out-private ~/cb_license.pem

    # print the public key line to paste into License.kt
    python scripts/issue_license.py --print-public-key --private-key ~/cb_license.pem

    # an institutional key, one year, 50 documents, 100 MB
    python scripts/issue_license.py \
        --private-key ~/cb_license.pem \
        --tenant-id northgate_poly \
        --tenant-name "Northgate Polytechnic" \
        --tier INSTITUTIONAL \
        --expires 2027-06-30 \
        --max-docs 50 --max-total-kb 102400

    # the founder's own key, bound to one install (long-press the tier label
    # on the licence screen to read the install id off the device)
    python scripts/issue_license.py \
        --private-key ~/cb_license.pem \
        --tenant-id founder --tenant-name "Campus Brain" \
        --tier OWNER --no-expiry --device-id 7f3c… \
        --max-docs 100000 --max-total-kb 10485760
"""
import argparse
import base64
import datetime as dt
import sys

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:  # pragma: no cover - an environment problem, not a bug
    print(
        "This script needs `cryptography` (pip install cryptography). It is a "
        "tooling dependency only and is never added to the Android build.",
        file=sys.stderr,
    )
    raise SystemExit(2)


# Must match LicenseKey.SCHEMA_VERSION in the app. An app that meets a version
# it does not know REJECTS the key outright rather than parsing the fields it
# recognises, so bumping this without shipping an app that understands it makes
# every key issued afterwards useless. That is the intended direction: a future
# field could be a restriction, and a parser cannot honour a restriction it
# cannot see by ignoring it.
SCHEMA_VERSION = 1

# RFC 4648, and the app's LicenseKey.B32 must be byte-identical to it. Base32
# rather than base64 because a key is dictated over a phone and typed on a
# phone keyboard: no case to get wrong, no `+` or `/` on a symbol page.
B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

TIERS = ("INSTITUTIONAL", "OWNER")


def _escape(s: str) -> str:
    """`|` and `%` only -- the separator, and the escape itself. Mirrors
    LicenseKey.escape. Escaping more would be a format nobody can hand-check
    against a key in an email."""
    return s.replace("%", "%25").replace("|", "%7C")


def build_body(
    tenant_id: str,
    tenant_name: str,
    tier: str,
    issued_at_ms: int,
    expires_at_ms: int | None,
    max_docs: int,
    max_total_kb: int,
    device_id: str | None,
) -> str:
    """The exact bytes the signature covers. Field for field, and in the same
    order as LicenseKey.body in the app; the two are a wire format and drift
    between them shows up as every key failing verification at once."""
    return "|".join(
        [
            str(SCHEMA_VERSION),
            tenant_id,
            _escape(tenant_name),
            tier,
            str(issued_at_ms),
            "" if expires_at_ms is None else str(expires_at_ms),
            str(max_docs),
            str(max_total_kb),
            device_id or "",
        ]
    )


def base32(data: bytes) -> str:
    """Unpadded RFC 4648 base32. `base64.b32encode` would do it, but it pads
    with `=` and the app's decoder rejects characters outside the alphabet --
    stripping the padding here keeps the two ends describing one format rather
    than one format plus a convention."""
    out = []
    buffer = 0
    bits = 0
    for byte in data:
        buffer = (buffer << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            out.append(B32[(buffer >> bits) & 0x1F])
    if bits:
        out.append(B32[(buffer << (5 - bits)) & 0x1F])
    return "".join(out)


def encode_key(body: str, signature: bytes) -> str:
    """[2-byte big-endian body length][body][DER signature], base32, in groups
    of four. The groups are for a human proof-reading the key against an email;
    the app strips them before decoding."""
    body_bytes = body.encode("utf-8")
    if len(body_bytes) > 0xFFFF:
        raise ValueError("licence body too long for a 2-byte length header")
    blob = len(body_bytes).to_bytes(2, "big") + body_bytes + signature
    text = base32(blob)
    return "CBI-" + "-".join(text[i:i + 4] for i in range(0, len(text), 4))


def load_private_key(path: str):
    """Fail closed: a missing, unreadable or wrong-curve key file is an error
    and never a fallback. There is no default key and no generated-on-the-fly
    key, because either would silently produce licences the shipped app cannot
    verify -- a failure that surfaces at the customer rather than here."""
    try:
        with open(path, "rb") as f:
            key = serialization.load_pem_private_key(f.read(), password=None)
    except OSError as e:
        raise SystemExit(f"cannot read private key at {path}: {e}")
    except ValueError as e:
        raise SystemExit(f"{path} is not a readable PEM private key: {e}")
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        raise SystemExit(f"{path} is not an EC private key")
    if key.curve.name != "secp256r1":
        raise SystemExit(
            f"{path} is on curve {key.curve.name}; the app verifies P-256 "
            "(secp256r1) only"
        )
    return key


def parse_date_ms(text: str) -> int:
    """A calendar date, interpreted as midnight UTC.

    A date and not a timestamp because an expiry is a thing written into a
    contract, and 'the 30th of June' is what both sides agreed to. UTC because
    the alternative is a key that expires at a different moment depending on
    where the issuing laptop was sitting."""
    try:
        day = dt.date.fromisoformat(text)
    except ValueError:
        raise SystemExit(f"--expires wants YYYY-MM-DD, got {text!r}")
    return int(
        dt.datetime(day.year, day.month, day.day, tzinfo=dt.timezone.utc).timestamp() * 1000
    )


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Issue a Campus Brain licence key, offline.",
    )
    p.add_argument("--private-key", help="PEM file holding the P-256 private key")
    p.add_argument("--generate-keypair", action="store_true",
                   help="create a new P-256 keypair and write the private half")
    p.add_argument("--out-private", help="where --generate-keypair writes the private key")
    p.add_argument("--print-public-key", action="store_true",
                   help="print the base64 SubjectPublicKeyInfo for License.kt")

    p.add_argument("--tenant-id", help="^[A-Za-z0-9_-]{1,64}$")
    p.add_argument("--tenant-name", help="shown on the licence screen")
    p.add_argument("--tier", choices=TIERS)
    p.add_argument("--expires", help="YYYY-MM-DD, midnight UTC")
    p.add_argument("--no-expiry", action="store_true", help="OWNER keys only")
    p.add_argument("--max-docs", type=int)
    p.add_argument("--max-total-kb", type=int)
    p.add_argument("--device-id", help="OWNER keys only; the app's install id")
    args = p.parse_args(argv)

    if args.generate_keypair:
        return generate(args)

    if not args.private_key:
        raise SystemExit("--private-key is required")
    key = load_private_key(args.private_key)

    if args.print_public_key:
        der = key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        print(base64.b64encode(der).decode())
        return 0

    # Every check below refuses rather than repairs. A clamped cap or a
    # defaulted tier is a licence nobody agreed to, printed as though they had.
    for flag in ("tenant_id", "tenant_name", "tier", "max_docs", "max_total_kb"):
        if getattr(args, flag) in (None, ""):
            raise SystemExit(f"--{flag.replace('_', '-')} is required")

    import re
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.tenant_id):
        raise SystemExit("--tenant-id must match ^[A-Za-z0-9_-]{1,64}$")
    if args.max_docs <= 0 or args.max_total_kb <= 0:
        raise SystemExit("--max-docs and --max-total-kb must be positive")

    if args.no_expiry and args.expires:
        raise SystemExit("--no-expiry and --expires are mutually exclusive")
    if args.no_expiry and args.tier != "OWNER":
        raise SystemExit(
            "only an OWNER key may be perpetual; an institutional licence that "
            "never expires is a renewal conversation that never happens"
        )
    if not args.no_expiry and not args.expires:
        raise SystemExit("--expires YYYY-MM-DD is required (or --no-expiry for OWNER)")

    expires_ms = None if args.no_expiry else parse_date_ms(args.expires)
    now_ms = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
    if expires_ms is not None and expires_ms <= now_ms:
        raise SystemExit("--expires is in the past; the app would reject this key")

    # The binding, and it is mandatory on OWNER and forbidden elsewhere. The
    # app enforces the same rule; issuing a key it will refuse is a support
    # call, so it is refused here where the mistake was actually made.
    if args.tier == "OWNER":
        if not args.device_id:
            raise SystemExit(
                "an OWNER key must be bound to one install: pass --device-id. "
                "Read it off the device by long-pressing the tier label on the "
                "licence screen."
            )
    elif args.device_id:
        raise SystemExit(
            "--device-id is for OWNER keys only. Institutional licences are "
            "sold per organisation, not per handset, and binding one would "
            "break 'reinstall on a new phone, re-enter the same string'."
        )

    body = build_body(
        tenant_id=args.tenant_id,
        tenant_name=args.tenant_name,
        tier=args.tier,
        issued_at_ms=now_ms,
        expires_at_ms=expires_ms,
        max_docs=args.max_docs,
        max_total_kb=args.max_total_kb,
        device_id=args.device_id,
    )
    signature = key.sign(body.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    print(encode_key(body, signature))
    return 0


def generate(args) -> int:
    """Create a founder keypair. Writes the private half and nothing else."""
    if not args.out_private:
        raise SystemExit("--generate-keypair needs --out-private")
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    # 'xb' so an existing key is never silently replaced. Overwriting the
    # private key invalidates every licence ever issued from it, in one
    # keystroke, with no way back.
    try:
        with open(args.out_private, "xb") as f:
            f.write(pem)
    except FileExistsError:
        raise SystemExit(
            f"{args.out_private} already exists. Refusing to overwrite: that "
            "would invalidate every licence issued from the existing key."
        )
    except OSError as e:
        raise SystemExit(f"cannot write {args.out_private}: {e}")

    der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    print(f"private key written to {args.out_private} — keep it off the repo")
    print("public key for LicenseKey.PUBLIC_KEY_B64:")
    print(base64.b64encode(der).decode())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
