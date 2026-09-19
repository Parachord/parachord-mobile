#!/usr/bin/env python3
"""
Generate / inspect / rotate the Apple Music **developer token** (ES256 JWT).

Why this exists
---------------
The token is signed with the team's `.p8` AuthKey and Apple caps it at 180
days. It used to be hand-pasted into three unconnected places, with nothing
watching its `exp`. In Sept 2026 it lapsed and Apple Music died across the
app — presenting to users as a bogus "Sign in to Apple Music" prompt that
looped forever, because no amount of signing in can fix an expired app key.

This script is the single source of truth for minting it, so the value can be
derived rather than remembered. The Android build calls `--ensure` and rotates
automatically before expiry; `--write` updates every checked-in consumer at
once, so the copies cannot drift apart.

Deliberately stdlib-only (signs via `openssl`) so it runs in a bare build
environment with no pip install step.

Config — put these in local.properties (all gitignored):

    APPLE_MUSIC_AUTHKEY_P8 = /path/to/AuthKey_XXXXXXXXXX.p8
    APPLE_MUSIC_KEY_ID     = XXXXXXXXXX     # optional, inferred from filename
    APPLE_MUSIC_TEAM_ID    = YYYYYYYYYY     # optional, inferred from current token

Usage:
    ./scripts/apple-music-token.py --check     # report expiry of every copy
    ./scripts/apple-music-token.py --print     # mint one, print to stdout
    ./scripts/apple-music-token.py --write     # mint + update every copy
    ./scripts/apple-music-token.py --ensure    # rotate ONLY if expiring soon
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL_PROPS = os.path.join(ROOT, "local.properties")
XCCONFIG = os.path.join(ROOT, "iosApp", "Parachord", "Secrets.xcconfig")

# Apple's hard ceiling. Anything longer is rejected at token creation.
MAX_DAYS = 180
# Rotate this far ahead of expiry so users are never the ones to discover it.
RENEW_WITHIN_DAYS = 30

KEY = "APPLE_MUSIC_DEVELOPER_TOKEN"
# (path, "how a line is written in this file")
TARGETS = [(LOCAL_PROPS, f"{KEY}=%s"), (XCCONFIG, f"{KEY} = %s")]


# ── properties ──────────────────────────────────────────────────────

def read_props(path: str) -> dict[str, str]:
    out: dict[str, str] = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip('"')
    return out


def config(props: dict[str, str], key: str) -> str:
    return (props.get(key) or os.environ.get(key) or "").strip()


# ── JWT ─────────────────────────────────────────────────────────────

def b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def decode_claims(token: str) -> dict | None:
    """Unverified payload claims, or None if unparseable."""
    parts = (token or "").split(".")
    if len(parts) != 3:
        return None
    seg = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        return json.loads(base64.urlsafe_b64decode(seg))
    except Exception:
        return None


def expires_at(token: str) -> int | None:
    claims = decode_claims(token)
    exp = claims.get("exp") if claims else None
    return exp if isinstance(exp, int) else None


def der_to_raw(der: bytes, size: int = 32) -> bytes:
    """DER SEQUENCE{INTEGER r, INTEGER s} -> fixed-width r||s, as JWS wants."""
    if not der or der[0] != 0x30:
        raise ValueError("signature is not a DER SEQUENCE")
    i = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    out = b""
    for _ in range(2):
        if der[i] != 0x02:
            raise ValueError("expected DER INTEGER in signature")
        length = der[i + 1]
        val = der[i + 2 : i + 2 + length]
        i += 2 + length
        out += val.lstrip(b"\x00").rjust(size, b"\x00")
    return out


def mint(p8_path: str, kid: str, team: str, days: int = MAX_DAYS) -> str:
    if not os.path.exists(p8_path):
        raise SystemExit(f"error: AuthKey not found: {p8_path}")
    if days > MAX_DAYS:
        raise SystemExit(f"error: Apple caps developer tokens at {MAX_DAYS} days")
    now = int(time.time())
    header = {"alg": "ES256", "kid": kid}
    payload = {"iss": team, "iat": now, "exp": now + days * 86400}
    signing_input = "{}.{}".format(
        b64u(json.dumps(header, separators=(",", ":")).encode()),
        b64u(json.dumps(payload, separators=(",", ":")).encode()),
    )
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            fh.write(signing_input.encode())
            tmp = fh.name
        proc = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", p8_path, tmp],
            capture_output=True,
        )
        if proc.returncode != 0:
            raise SystemExit(f"error: openssl signing failed: {proc.stderr.decode().strip()}")
        return f"{signing_input}.{b64u(der_to_raw(proc.stdout))}"
    finally:
        if tmp and os.path.exists(tmp):
            os.unlink(tmp)


# ── file updates ────────────────────────────────────────────────────

def write_token(token: str) -> list[str]:
    """Update every checked-in copy that exists. Returns paths touched."""
    touched = []
    pattern = re.compile(rf"^\s*{KEY}\s*=")
    for path, fmt in TARGETS:
        if not os.path.exists(path):
            print(f"  skip  {os.path.relpath(path, ROOT)} (not present)")
            continue
        lines = open(path, encoding="utf-8").read().split("\n")
        hits = 0
        for i, line in enumerate(lines):
            if pattern.match(line):
                lines[i] = fmt % token
                hits += 1
        if hits == 0:
            # Append rather than silently doing nothing.
            if lines and lines[-1] == "":
                lines.insert(len(lines) - 1, fmt % token)
            else:
                lines.append(fmt % token)
            hits = 1
        open(path, "w", encoding="utf-8").write("\n".join(lines))
        print(f"  wrote {os.path.relpath(path, ROOT)}")
        touched.append(path)
    return touched


def describe(token: str) -> str:
    exp = expires_at(token)
    if not token:
        return "absent"
    if exp is None:
        return "unparseable"
    left = (exp - time.time()) / 86400
    when = time.strftime("%Y-%m-%d", time.gmtime(exp))
    if left < 0:
        return f"EXPIRED {when} ({abs(left):.0f}d ago)"
    return f"valid until {when} ({left:.0f}d left)"


def cmd_check() -> int:
    worst = 0
    for path, _ in TARGETS:
        rel = os.path.relpath(path, ROOT)
        if not os.path.exists(path):
            print(f"{rel}: (not present)")
            continue
        token = config(read_props(path), KEY)
        state = describe(token)
        print(f"{rel}: {state}")
        if "EXPIRED" in state or state == "absent":
            worst = 1
    print(
        "\nreminder: the CI secret APPLE_MUSIC_DEVELOPER_TOKEN "
        "(.github/workflows/build.yml) is a THIRD copy this script cannot see "
        "— rotate it too."
    )
    return worst


def cmd_check_env(renew_within_days: int) -> int:
    """
    Check the token supplied via the environment — i.e. the CI secret, the one
    copy this script cannot rotate and no local build will ever renew.

    Exits non-zero when it is absent, unreadable, expired, or inside the
    renewal window, so a scheduled job can fail before users are locked out
    rather than after. Prints only the expiry date — never the token.
    """
    token = os.environ.get(KEY, "").strip()
    if not token:
        print(f"::error::{KEY} is not set (secret missing or empty)")
        return 1
    exp = expires_at(token)
    if exp is None:
        print(f"::error::{KEY} is not a readable JWT — cannot determine expiry")
        return 1

    days_left = (exp - time.time()) / 86400
    when = time.strftime("%Y-%m-%d", time.gmtime(exp))
    if days_left < 0:
        print(f"::error::Apple Music developer token EXPIRED on {when} "
              f"({abs(days_left):.0f} days ago). Apple Music is broken in releases built from this secret.")
        return 1
    if days_left <= renew_within_days:
        print(f"::error::Apple Music developer token expires {when} "
              f"({days_left:.0f} days). Rotate it before it lapses.")
        return 1
    print(f"Apple Music developer token valid until {when} ({days_left:.0f} days left)")
    return 0


def resolve_config() -> tuple[str, str, str]:
    props = read_props(LOCAL_PROPS)
    p8 = config(props, "APPLE_MUSIC_AUTHKEY_P8")
    if not p8:
        raise SystemExit(
            "error: APPLE_MUSIC_AUTHKEY_P8 is not set in local.properties.\n"
            "       Point it at your AuthKey_XXXXXXXXXX.p8 from "
            "https://developer.apple.com/account/resources/authkeys/list"
        )
    p8 = os.path.expanduser(p8)
    kid = config(props, "APPLE_MUSIC_KEY_ID")
    if not kid:
        m = re.search(r"AuthKey_([A-Z0-9]{10})\.p8$", p8)
        if not m:
            raise SystemExit("error: set APPLE_MUSIC_KEY_ID (could not infer from filename)")
        kid = m.group(1)
    team = config(props, "APPLE_MUSIC_TEAM_ID")
    if not team:
        # The existing token already carries the team id as `iss`.
        claims = decode_claims(config(props, KEY)) or {}
        team = claims.get("iss", "")
        if not team:
            raise SystemExit("error: set APPLE_MUSIC_TEAM_ID in local.properties")
    return p8, kid, team


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--check", action="store_true", help="report expiry of each copy")
    g.add_argument("--check-env", action="store_true",
                   help=f"check the {KEY} env var (the CI secret); non-zero if expiring")
    g.add_argument("--print", dest="do_print", action="store_true", help="mint and print")
    g.add_argument("--write", action="store_true", help="mint and update every copy")
    g.add_argument("--ensure", action="store_true", help="rotate only if expiring soon")
    ap.add_argument("--days", type=int, default=MAX_DAYS, help=f"lifetime (max {MAX_DAYS})")
    ap.add_argument("--renew-within", type=int, default=RENEW_WITHIN_DAYS,
                    help=f"days before expiry that counts as due (default {RENEW_WITHIN_DAYS})")
    args = ap.parse_args()

    if args.check_env:
        return cmd_check_env(args.renew_within)

    if args.check or not any([args.do_print, args.write, args.ensure]):
        return cmd_check()

    if args.ensure:
        token = config(read_props(LOCAL_PROPS), KEY)
        exp = expires_at(token)
        if exp and exp - time.time() > RENEW_WITHIN_DAYS * 86400:
            print(f"Apple Music developer token {describe(token)} — no rotation needed")
            return 0
        print(f"Apple Music developer token {describe(token)} — rotating")

    p8, kid, team = resolve_config()
    token = mint(p8, kid, team, args.days)

    if args.do_print:
        print(token)
        return 0

    print(f"minted (kid={kid}, iss={team}, {describe(token)})")
    write_token(token)
    print(
        "\nNOTE: also update the GitHub Actions secret "
        "APPLE_MUSIC_DEVELOPER_TOKEN — release builds read that, not "
        "local.properties:\n  gh secret set APPLE_MUSIC_DEVELOPER_TOKEN"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
