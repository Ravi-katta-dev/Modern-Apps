"""Upload any local file to R2 as planet3.mamaps (bucket: maps).

Usage: python upload_planet.py <local-file>
       python upload_planet.py --manifest <overlay-dir> --overlay-seq N --base-build-id HEX
Verifies server-side size matches, then deletes nothing local. Progress
prints every ~1 GB.

Serves https://data.maproj.org/planet3.mamaps, which the apps'
basemap URLs point at (library/map urls.rs, maps MapTileCache.kt).

The --manifest form publishes a patch-overlay update: it uploads each
overlay-*.mamaps segment from the overlay dir under overlays/<seq>/,
then writes planet3.manifest.json (base pin + segment list + hashes).
The manifest is the only file the client fetches on a check.

SECURITY: credentials/config come from ENVIRONMENT VARIABLES, never baked
into this file (the analysis/ original carried hardcoded keys and must
stay gitignored there). Never paste keys on the command line — `export`
them instead, ideally from a secret store.

Required env:
  R2_ENDPOINT           S3 API endpoint, e.g. https://<ACCOUNT_ID>.r2.cloudflarestorage.com
  R2_ACCESS_KEY_ID      R2 access key id
  R2_SECRET_ACCESS_KEY  R2 secret access key
Optional env:
  R2_BUCKET             Default: maps
  R2_CDN                Default: https://data.maproj.org (URL prefix written into the manifest)
"""
import hashlib
import json
import os
import sys
import threading

import boto3
from boto3.s3.transfer import TransferConfig

BUCKET = os.environ.get("R2_BUCKET", "maps")
KEY = "planet3.mamaps"
MANIFEST_KEY = "planet3.manifest.json"
CDN = os.environ.get("R2_CDN", "https://data.maproj.org")

PART_SIZE = 100 * 1024 * 1024  # 100 MB parts, 10-way concurrency

MANIFEST_VERSION = 1
MIN_CLIENT_COMPAT = 1


def main() -> int:
    if len(sys.argv) >= 2 and sys.argv[1] == "--manifest":
        return publish_manifest(sys.argv[2:])
    if len(sys.argv) != 2:
        print(f"usage: {os.path.basename(sys.argv[0])} <local-file>", flush=True)
        print(f"   or: {os.path.basename(sys.argv[0])} --manifest <overlay-dir> --overlay-seq N --base-build-id HEX [--base-len N]", flush=True)
        return 2
    missing = [v for v in ("R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY")
               if not os.environ.get(v)]
    if missing:
        print(f"missing env: {', '.join(missing)} (see module docstring)", flush=True)
        return 2
    src = sys.argv[1]
    if not os.path.isfile(src):
        print(f"no such file: {src}", flush=True)
        return 2
    size = os.path.getsize(src)
    print(f"uploading {src} ({size / 1e9:.1f} GB) to s3://{BUCKET}/{KEY}", flush=True)

    s3 = boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )
    config = TransferConfig(
        multipart_threshold=PART_SIZE,
        multipart_chunksize=PART_SIZE,
        max_concurrency=10,
        use_threads=True,
    )

    lock = threading.Lock()
    done = [0]
    last_mark = [0]

    def progress(n: int) -> None:
        with lock:
            done[0] += n
            gb = done[0] / 1e9
            if gb - last_mark[0] >= 1.0 or done[0] >= size:
                last_mark[0] = gb
                pct = 100.0 * done[0] / size
                print(f"  {gb:.1f}/{size / 1e9:.1f} GB ({pct:.1f}%)", flush=True)

    try:
        s3.upload_file(src, BUCKET, KEY, Config=config, Callback=progress)
    except Exception as e:
        print(f"UPLOAD FAILED: {e}", flush=True)
        return 1

    remote = s3.head_object(Bucket=BUCKET, Key=KEY)["ContentLength"]
    print(f"done. remote size {remote} bytes, local {size} bytes", flush=True)
    return 0 if remote == size else 2


def publish_manifest(argv: list) -> int:
    """Upload overlay segments + write planet3.manifest.json.

    Args: <overlay-dir> --overlay-seq N --base-build-id HEX [--base-len N]
    The overlay dir holds overlay-*.mamaps (from mamaps_overlay) whose
    descriptor overlay.json carries the overlay_build_id. Segments upload
    to overlays/<seq>/overlay-SSSS.mamaps; the manifest lists their CDN
    URLs + lengths + SHA-256. Sections (chunked sidecars) ride the same
    manifest when present as <kind>.section files (not yet emitted).
    """
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("overlay_dir")
    ap.add_argument("--overlay-seq", type=int, required=True)
    ap.add_argument("--base-build-id", required=True,
                    help="hex base build_id the overlay applies to, e.g. 0x0355...")
    ap.add_argument("--base-len", type=int, default=None,
                    help="base length in bytes (else read from overlay.json sidecar)")
    args = ap.parse_args(argv)
    missing = [v for v in ("R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY")
               if not os.environ.get(v)]
    if missing:
        print(f"missing env: {', '.join(missing)} (see module docstring)", flush=True)
        return 2
    overlay_dir = args.overlay_dir
    descriptor_path = os.path.join(overlay_dir, "overlay.json")
    try:
        descriptor = json.load(open(descriptor_path))
    except (OSError, ValueError) as e:
        print(f"cannot read {descriptor_path}: {e} (run mamaps_overlay first)", flush=True)
        return 2
    s3 = boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )
    config = TransferConfig(
        multipart_threshold=PART_SIZE,
        multipart_chunksize=PART_SIZE,
        max_concurrency=10,
        use_threads=True,
    )
    segments = []
    for name in sorted(os.listdir(overlay_dir)):
        if not (name.startswith("overlay-") and name.endswith(".mamaps")):
            continue
        local = os.path.join(overlay_dir, name)
        key = f"overlays/{args.overlay_seq:04d}/{name}"
        size = os.path.getsize(local)
        print(f"uploading {local} ({size / 1e6:.1f} MB) to s3://{BUCKET}/{key}", flush=True)
        try:
            s3.upload_file(local, BUCKET, key, Config=config)
        except Exception as e:
            print(f"UPLOAD FAILED: {e}", flush=True)
            return 1
        remote = s3.head_object(Bucket=BUCKET, Key=key)["ContentLength"]
        if remote != size:
            print(f"size mismatch for {key}: remote {remote} vs local {size}", flush=True)
            return 2
        digest = sha256_file(local)
        seq = int(name[len("overlay-"):len("overlay-") + 4])
        segments.append({
            "url": f"{CDN}/{key}",
            "len": size,
            "sha256": digest,
            "seq": seq,
        })
    if not segments:
        print(f"no overlay-*.mamaps in {overlay_dir}", flush=True)
        return 2
    manifest = {
        "version": MANIFEST_VERSION,
        "base": {
            "url": f"{CDN}/{KEY}",
            "build_id": args.base_build_id,
            "len": args.base_len or 0,
        },
        "overlay_seq": args.overlay_seq,
        "overlay_build_id": descriptor.get("overlay_build_id", "0x0"),
        "segments": segments,
        "sections": [],
        "min_client": MIN_CLIENT_COMPAT,
    }
    body = json.dumps(manifest, indent=1).encode()
    s3.put_object(Bucket=BUCKET, Key=MANIFEST_KEY, Body=body,
                  ContentType="application/json")
    print(f"wrote s3://{BUCKET}/{MANIFEST_KEY} "
          f"({len(segments)} segments, overlay_build_id={manifest['overlay_build_id']})",
          flush=True)
    return 0


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


if __name__ == "__main__":
    sys.exit(main())
