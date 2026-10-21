"""Fingerprint executable contents and source text consistently across machines."""

import hashlib
import zipfile


def source_checksum(path):
    """Hash source text with checkout CRLF line endings normalized to LF."""
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def engine_fingerprint(path):
    """Hash JAR entry contents independently of ZIP order and timestamps.

    Maven's generated pom metadata and the manifest are excluded. The runner
    invokes its own main class, so the JAR manifest does not select execution.
    All other files, including bytecode, logging configuration, and service
    registrations, contribute to this fingerprint.
    """
    digest = hashlib.sha256()
    try:
        with zipfile.ZipFile(path) as archive:
            entries = [entry for entry in archive.infolist() if not entry.is_dir()
                       and entry.filename != "META-INF/MANIFEST.MF"
                       and not entry.filename.startswith("META-INF/maven/")]
            if len({entry.filename for entry in entries}) != len(entries):
                raise ValueError("Engine JAR has duplicate runtime entries")
            for entry in sorted(entries, key=lambda item: item.filename):
                name = entry.filename.encode("utf-8")
                digest.update(len(name).to_bytes(8, "big"))
                digest.update(name)
                digest.update(entry.file_size.to_bytes(8, "big"))
                with archive.open(entry) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(block)
    except zipfile.BadZipFile as error:
        raise ValueError(f"Invalid engine JAR: {path}") from error
    return digest.hexdigest()
