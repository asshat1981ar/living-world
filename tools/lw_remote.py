"""Push living-world to awsbox and run commands there over SSM (no inbound SSH needed).

Adapted from distill/foundry-dev/tools/ssm_deploy.py, but targets ~/living-world with its own lock and temp files.
NEVER use ssm_deploy.py --push for this repo: it replaces ~/foundry.

    python3 tools/lw_remote.py --push --gradle check
    python3 tools/lw_remote.py --run 'java -version'
    python3 tools/lw_remote.py --run-detached 'cd ~/living-world && ./gradlew check > /tmp/lw-check.log 2>&1; echo EXIT $? >> /tmp/lw-check.log'
    python3 tools/lw_remote.py --pull gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar

The pushed file set is `git ls-files -co --exclude-standard`, so .gitignore (gguf, _archive, build output) is honoured.
Remote build state (.gradle, build/, .kotlin/, local.properties) is never deleted by a push.
SSM caps output at ~24 KB: remote commands should print summaries; every helper script ends with an END marker.
Needs a live local `aws` session (aws login --remote).
"""

import argparse
import base64
import fcntl
import hashlib
import json
import shlex
import subprocess
import sys
import tarfile
import time
import zlib
from pathlib import Path

INSTANCE = "i-060276259b02f38e0"
REGION = "us-east-2"
REMOTE_ROOT = "~/living-world"
CHUNK = 40_000  # base64 chars per SSM command (well under the API limit)
PULL_CHUNK = 18_000  # stays under the 24 KB output cap
LOCK = "/tmp/lw-deploy.lock"
LIVE_JOB_MARKER = "/tmp/awsbox-live-job"
KEEP = [".gradle", "build", ".kotlin", "local.properties"]  # remote-only build state, never pushed or deleted
REPO = Path(__file__).resolve().parents[1]
GRADLE = "cd ~/living-world && nice -n 10 ./gradlew --no-daemon --console=plain -Dorg.gradle.workers.max=3"


def aws(*args: str) -> dict:
    p = subprocess.run(["aws", *args, "--region", REGION, "--output", "json"], capture_output=True, text=True, check=False)
    if p.returncode:
        raise SystemExit(f"aws {args[0]} {args[1]} failed: {p.stderr.strip()[:300]}")
    return json.loads(p.stdout) if p.stdout.strip() else {}


def run(script: str, timeout_s: int = 3600) -> tuple[str, str, str]:
    """Run a shell script on awsbox as `ubuntu`; returns (status, stdout, stderr)."""
    # shlex.quote, not json.dumps: SSM's root shell must not expand $HOME etc. before `ubuntu` sees it.
    inner = f"export PATH=$HOME/.local/bin:$PATH; export ANDROID_HOME=$HOME/android-sdk; {script}"
    wrapped = f"sudo -u ubuntu -H bash -lc {shlex.quote(inner)}"
    sent = aws(
        "ssm", "send-command", "--instance-ids", INSTANCE, "--document-name", "AWS-RunShellScript",
        "--timeout-seconds", str(timeout_s), "--parameters", json.dumps({"commands": [wrapped]}),
    )
    cid = sent["Command"]["CommandId"]
    deadline = time.time() + timeout_s + 60
    while time.time() < deadline:
        time.sleep(3)
        inv = aws("ssm", "get-command-invocation", "--command-id", cid, "--instance-id", INSTANCE)
        if inv["Status"] in ("Success", "Failed", "TimedOut", "Cancelled"):
            return inv["Status"], inv.get("StandardOutputContent", ""), inv.get("StandardErrorContent", "")
    raise SystemExit("timed out waiting for the remote command")


def _local_files() -> dict[str, Path]:
    out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=REPO, capture_output=True, check=True)
    rels = [r for r in out.stdout.decode().split("\0") if r]
    return {r: REPO / r for r in sorted(rels) if (REPO / r).is_file()}


REMOTE_SCRIPT = r"""
import hashlib, os, sys
ROOT = os.path.expanduser("~/living-world")
KEEP = set(%(keep)r)
def walk():
    out = {}
    for d, dirs, files in os.walk(ROOT):
        dirs[:] = [x for x in dirs if x not in KEEP and x != ".git"]
        for f in files:
            p = os.path.join(d, f)
            rel = os.path.relpath(p, ROOT)
            if f in KEEP or os.path.islink(p) or not os.path.isfile(p):
                continue
            with open(p, "rb") as fh:
                out[rel] = hashlib.sha256(fh.read()).hexdigest()
    return out
mode = sys.argv[1]
if not os.path.isdir(ROOT):
    print("NOTREE"); print("END"); sys.exit()
cur = walk()
if mode == "digest":
    print("DIGEST", hashlib.sha256("".join(h + "  " + p + chr(10) for p, h in sorted(cur.items())).encode()).hexdigest())
else:
    want = {}
    for line in open("/tmp/lw-deploy.manifest").read().splitlines():
        h, p = line.split("  ", 1)
        want[p] = h
    for p, h in sorted(want.items()):
        if cur.get(p) != h:
            print("CHANGED", p)
    for p in sorted(cur):
        if p not in want:
            print("DELETE", p)
print("END")
""" % {"keep": KEEP}


def _remote_script(mode: str, manifest: dict[str, str] | None = None) -> str:
    parts = [f"echo {base64.b64encode(REMOTE_SCRIPT.encode()).decode()} | base64 -d > /tmp/lw-deploy.diff.py"]
    if manifest is not None:
        text = "".join(f"{h}  {p}\n" for p, h in sorted(manifest.items()))
        z = base64.b64encode(zlib.compress(text.encode(), 9)).decode()
        parts.append(
            f"echo {z} | base64 -d | python3 -c 'import sys,zlib;sys.stdout.buffer.write(zlib.decompress(sys.stdin.buffer.read()))'"
            " > /tmp/lw-deploy.manifest"
        )
    parts.append(f"python3 /tmp/lw-deploy.diff.py {mode}")
    return "; ".join(parts)


def _digest(hashes: dict[str, str]) -> str:
    return hashlib.sha256("".join(f"{h}  {p}\n" for p, h in sorted(hashes.items())).encode()).hexdigest()


def _upload(tgz: Path) -> None:
    b64 = base64.b64encode(tgz.read_bytes()).decode()
    print(f"sending {tgz.stat().st_size:,} bytes in {len(range(0, len(b64), CHUNK))} chunk(s) over SSM", flush=True)
    run("rm -f /tmp/lw-deploy.b64")
    for i in range(0, len(b64), CHUNK):
        st, _, err = run(f"printf '%s' '{b64[i : i + CHUNK]}' >> /tmp/lw-deploy.b64")
        if st != "Success":
            raise SystemExit(f"chunk upload failed: {err[:200]}")


def push() -> None:
    """Delta push (only files whose sha256 differs), then verify the whole tree with one digest. Serialised by a lock."""
    with open(LOCK, "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        local = _local_files()
        hashes = {rel: hashlib.sha256(p.read_bytes()).hexdigest() for rel, p in local.items()}
        st, out, _ = run(_remote_script("diff", hashes))
        lines = out.splitlines()
        if st != "Success" or not lines or lines[-1] != "END" or lines[0] == "NOTREE":
            changed, deleted, mode = sorted(local), [], "full"
        else:
            changed = sorted(ln.split(" ", 1)[1] for ln in lines if ln.startswith("CHANGED ") and ln.split(" ", 1)[1] in local)
            deleted = sorted(ln.split(" ", 1)[1] for ln in lines if ln.startswith("DELETE "))
            mode = "delta"
        print(f"{mode} push: {len(changed)} changed/new, {len(deleted)} deleted, {len(local) - len(changed)} unchanged", flush=True)
        tgz = Path("/tmp/lw-deploy.tgz")
        if changed:
            with tarfile.open(tgz, "w:gz") as t:
                for rel in changed:
                    t.add(local[rel], arcname=rel)
            _upload(tgz)
        steps = ["set -e", f"mkdir -p {REMOTE_ROOT}"]
        if changed:
            steps.append(f"base64 -d /tmp/lw-deploy.b64 > /tmp/lw-deploy.tgz; tar xzf /tmp/lw-deploy.tgz -C {REMOTE_ROOT}")
        if deleted:
            steps.append(f"cd {REMOTE_ROOT} && rm -f -- " + shlex.join(deleted))
        steps += ["rm -f /tmp/lw-deploy.b64 /tmp/lw-deploy.tgz", _remote_script("digest")]
        st, out, err = run("; ".join(steps))
        if st != "Success":
            raise SystemExit(f"apply failed: {(err or out).strip()[:300]}")
        got = next((ln.split()[1] for ln in out.splitlines() if ln.startswith("DIGEST ")), "")
        if got != _digest(hashes):
            raise SystemExit(f"tree digest mismatch after {mode} push (remote {got[:12]})")
        print(f"pushed ok: {len(local)} files, tree verified identical", flush=True)


def pull(remote_rel: str, local_rel: str) -> None:
    """Copy one (small) file from ~/living-world on awsbox into the repo, verified by sha256."""
    q = shlex.quote(remote_rel)
    st, out, err = run(f"cd {REMOTE_ROOT} && sha256sum {q} | cut -d' ' -f1 && base64 -w0 {q} | wc -c")
    if st != "Success":
        raise SystemExit(f"pull failed: {err.strip()[:200]}")
    want, size = out.split()[0], int(out.split()[1])
    b64 = ""
    for i in range(0, size, PULL_CHUNK):
        st, part, err = run(f"cd {REMOTE_ROOT} && base64 -w0 {q} | cut -c {i + 1}-{i + PULL_CHUNK}")
        if st != "Success":
            raise SystemExit(f"pull chunk failed: {err.strip()[:200]}")
        b64 += part.strip()
    data = base64.b64decode(b64)
    if hashlib.sha256(data).hexdigest() != want:
        raise SystemExit("pulled file hash mismatch")
    dest = REPO / local_rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    print(f"pulled {remote_rel} -> {local_rel} ({len(data):,} bytes, sha256 {want[:12]})")


def wait_for_live_job() -> None:
    """A live-model job on awsbox owns the cores; do not start a build under it."""
    for _ in range(240):
        st, out, _ = run(f"test -e {LIVE_JOB_MARKER} && cat {LIVE_JOB_MARKER} || echo NONE")
        if out.strip() == "NONE":
            return
        print(f"a live-model job is running: {out.strip()[:120]}; waiting", flush=True)
        time.sleep(60)
    raise SystemExit("live-model job still running after 4 h")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--push", action="store_true", help="delta-push the repo to ~/living-world")
    ap.add_argument("--gradle", help="gradle arguments to run on awsbox (waits for live jobs; prints the tail)")
    ap.add_argument("--tail", type=int, default=120, help="lines of gradle output to print")
    ap.add_argument("--run", help="shell command to run on awsbox and wait for")
    ap.add_argument("--run-detached", help="shell command to start in the background (returns at once)")
    ap.add_argument("--pull", nargs=2, metavar=("REMOTE", "LOCAL"), help="copy a small file back from ~/living-world")
    a = ap.parse_args()
    if a.push:
        push()
    rc = 0
    if a.gradle:
        wait_for_live_job()
        log = "/tmp/lw-gradle.log"
        st, out, err = run(
            f"{GRADLE} {a.gradle} > {log} 2>&1; echo \"GRADLE_EXIT $?\" >> {log}; "
            f"grep -E '^(FAILURE|BUILD|e: |w: .*deprecat)|tests completed|FAILED' {log} | head -60; echo ----; tail -n {a.tail} {log}; echo END"
        )
        print(out.rstrip())
        rc = 0 if "GRADLE_EXIT 0" in out else 1
    if a.run_detached:
        st, out, err = run(f"setsid nohup bash -c {shlex.quote(a.run_detached)} > /dev/null 2>&1 < /dev/null & echo started")
        print(st, out.strip(), err.strip()[:200])
    if a.run:
        st, out, err = run(a.run)
        print(out.rstrip())
        if err.strip():
            print("[stderr]", err.strip()[-600:], file=sys.stderr)
        rc = rc or (0 if st == "Success" else 1)
    if a.pull:
        pull(*a.pull)
    return rc


if __name__ == "__main__":
    sys.exit(main())
