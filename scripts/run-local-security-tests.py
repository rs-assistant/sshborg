#!/usr/bin/env python3
"""Run the JVM suite against disposable keys, file corpora, and a loopback SSH/SFTP server."""

import argparse
import asyncio
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
from xml.etree import ElementTree

import asyncssh


REPO = Path(__file__).resolve().parent.parent
TEST_USER = "sshborg-fixture"


def make_key(directory, name, algorithm, password, pem=False):
    command = ["ssh-keygen", "-q", "-t", algorithm, "-N", password,
               "-C", "disposable-sshborg-test", "-f", str(directory / name)]
    if algorithm == "rsa":
        command += ["-b", "2048"]
    if pem:
        command += ["-m", "PEM"]
    result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if result.returncode:
        # Do not include argv: it contains a generated, test-only key passphrase.
        raise RuntimeError(f"ssh-keygen failed while creating fixture {name}")


def fixtures(root):
    corpus, keys, editor, home = [root / name for name in ("corpus", "keys", "editor", "remote")]
    for directory in (corpus, keys, editor, home):
        directory.mkdir(mode=0o700)
    password = secrets.token_urlsafe(24)
    utf8_password = secrets.token_urlsafe(16) + "-é-🔑"
    for name, algorithm, pem in (
        ("k_ed25519", "ed25519", False), ("k_rsa", "rsa", False),
        ("k_ecdsa", "ecdsa", False), ("k_rsa_pem", "rsa", True),
    ):
        make_key(corpus, name, algorithm, password, pem)
    make_key(corpus, "k_utf8", "ed25519", utf8_password)
    make_key(corpus, "k_plain", "ed25519", "")
    make_key(corpus, "pkcs8-source", "rsa", "", pem=True)
    result = subprocess.run(
        ["openssl", "pkcs8", "-topk8", "-v2", "aes-256-cbc",
         "-in", str(corpus / "pkcs8-source"), "-out", str(corpus / "k_pkcs8"),
         "-passout", "env:SSHBORG_FIXTURE_PASSWORD"],
        env={**os.environ, "SSHBORG_FIXTURE_PASSWORD": password},
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    if result.returncode:
        raise RuntimeError("OpenSSL could not create the encrypted PKCS#8 fixture")
    for name, algorithm in (("i_ed25519", "ed25519"), ("i_rsa", "rsa"), ("i_ecdsa", "ecdsa")):
        make_key(keys, name, algorithm, password)
    make_key(keys, "i_plain", "ed25519", "")

    for encoding, text in (
        ("utf-8", "test é 中文 🔑\n"), ("utf-16", "test é 中文\r\n"),
        ("utf-32", "test é 中文\n"), ("iso-8859-1", "caffè\r\n"),
        ("cp1252", "€ café\n"), ("cp1251", "тест\n"),
        ("shift_jis", "テスト\r\n"), ("euc-kr", "테스트\n"),
        ("gb18030", "测试\n"), ("big5", "測試\n"),
    ):
        (editor / encoding).write_bytes(text.encode(encoding))
    (editor / "binary").write_bytes(bytes(range(256)) * 16)
    (editor / "empty").write_bytes(b"")
    (editor / "mixed-endings").write_bytes(b"one\r\ntwo\nthree\r\n")
    (editor / "long-line").write_bytes(b"x" * 20_000)
    (editor / "large-text").write_bytes(b"test line\n" * 100_000)
    (editor / "executable").write_bytes(b"#!/bin/sh\nprintf 'fixture\\n'\n")
    variables = {
        "SSHBORG_KEY_CORPUS": str(corpus), "SSHBORG_KEY_CORPUS_PASSPHRASE": password,
        "SSHBORG_KEY_CORPUS_UTF8_PASSPHRASE": utf8_password,
        "SSHBORG_EDITOR_CORPUS": str(editor), "SSHBORG_SSH_KEY_DIR": str(keys),
        "SSHBORG_SSH_KEY_PASSPHRASE": password,
    }
    return keys, home, variables


async def run_suite(root, offline):
    keys, home, variables = fixtures(root)
    authorized = [asyncssh.import_public_key(path.read_bytes()) for path in keys.glob("*.pub")]
    connections = set()
    port = 0

    class Server(asyncssh.SSHServer):
        def connection_made(self, connection):
            self.connection = connection
            connections.add(connection)

        def connection_lost(self, exc):
            connections.discard(self.connection)

        def begin_auth(self, username):
            return True

        def public_key_auth_supported(self):
            return True

        def validate_public_key(self, username, key):
            return username == TEST_USER and key in authorized

        def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
            return dest_host == "127.0.0.1" and dest_port == port

    async def shell(process):
        if process.command:
            process.exit(126)
            return
        child = await asyncio.create_subprocess_exec(
            "/bin/sh", cwd=home, env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"},
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        waits = []
        try:
            await process.redirect(stdin=child.stdin, stdout=child.stdout, stderr=child.stderr)
            waits = [asyncio.create_task(child.wait()), asyncio.create_task(process.wait_closed())]
            await asyncio.wait(waits, return_when=asyncio.FIRST_COMPLETED)
            if child.returncode is not None:
                process.exit(child.returncode)
        finally:
            if child.returncode is None:
                child.terminate()
                try:
                    await asyncio.wait_for(child.wait(), 5)
                except asyncio.TimeoutError:
                    child.kill()
                    await child.wait()
            for task in waits:
                task.cancel()
            await asyncio.gather(*waits, return_exceptions=True)

    server = await asyncssh.create_server(
        Server, "127.0.0.1", 0, server_host_keys=[asyncssh.generate_private_key("ssh-ed25519")],
        process_factory=shell, encoding=None,
        sftp_factory=lambda channel: asyncssh.SFTPServer(channel, chroot=os.fsencode(home)),
    )
    port = server.get_port()
    variables.update(SSHBORG_SSH_HOST="127.0.0.1", SSHBORG_SSH_PORT=str(port), SSHBORG_SSH_USER=TEST_USER)
    command = [str(REPO / "gradlew"), "--no-daemon", "--console=plain", "--max-workers=2",
               "-Pkotlin.compiler.execution.strategy=in-process", ":app:testDebugUnitTest", "--rerun"]
    if offline:
        command.insert(1, "--offline")
    gradle = None
    try:
        print("Running JVM tests with disposable key/editor corpora and loopback SSH/SFTP.", flush=True)
        gradle = await asyncio.create_subprocess_exec(*command, cwd=REPO, env={**os.environ, **variables})
        result = await asyncio.wait_for(gradle.wait(), 600)
        if result:
            return result
        return verify_results()
    finally:
        if gradle is not None and gradle.returncode is None:
            gradle.terminate()
            await gradle.wait()
        server.close()
        await server.wait_closed()
        for connection in list(connections):
            connection.close()
        await asyncio.gather(*(connection.wait_closed() for connection in list(connections)), return_exceptions=True)


def verify_results():
    counts = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    required = {"KeyImportTest", "SshIntegrationTest", "SftpIntegrationTest", "TextFileTest"}
    for report in (REPO / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
        suite = ElementTree.parse(report).getroot()
        required.discard(suite.get("name", "").rsplit(".", 1)[-1])
        for name in counts:
            counts[name] += int(suite.get(name, 0))
    print("JVM results:", counts, flush=True)
    if required or counts["tests"] == 0 or any(counts[name] for name in ("failures", "errors", "skipped")):
        print("FAIL: required suites must execute without failures, errors, or skipped tests.", flush=True)
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies only")
    args = parser.parse_args()
    if (REPO / "test.properties").exists():
        raise SystemExit("Refusing to override test.properties: move your local server configuration aside first.")
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="sshborg-security-tests-") as directory:
        return asyncio.run(run_suite(Path(directory), args.offline))


if __name__ == "__main__":
    raise SystemExit(main())
