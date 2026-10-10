"""Generate golden vectors for the MassDroid Sendspin Noise unit tests.

The vectors come from the same code the Music Assistant server runs: aiosendspin's Noise
constants, psk_id derivation and fragmenter, and the noiseprotocol library it builds its
sessions on. The server side plays the Noise initiator exactly as
aiosendspin.noise.session._build_conn does, with one difference: both ephemeral keys are fixed
so the output is deterministic. MassDroid is the responder, so its expected handshake message 2
and its expected transport frames are produced by a noiseprotocol responder with the same keys.

After the first handshake and a few transport frames, the server re-handshakes in band onto a
second PSK, the way aiosendspin's run_rehandshake_server does: both handshake messages travel as
noise/handshake JSON inside the old transport, the prologue is the previous handshake hash, and
both sides swap to the new session only after message 2.

Run it inside the MA container, where aiosendspin is installed:

    ssh mass 'docker exec -i music-assistant-server python3 -' < core/src/test/resources/sendspin/generate_noise_vectors.py \
        > core/src/test/resources/sendspin/noise_vectors.json
"""

from __future__ import annotations

import hashlib
import json

from aiosendspin.noise.constants import SENTINEL_PSK
from aiosendspin.noise.keys import Identity, b64url_encode, psk_id_for
from aiosendspin.noise.pairing_token import PSKPairingToken, decode_token, encode_token
from aiosendspin.noise.session import NoiseCipherSuite
from aiosendspin.noise.wire import _fragment
from noise.connection import Keypair, NoiseConnection

SUITE = NoiseCipherSuite.AESGCM


def fixed(label: str) -> bytes:
    """Return 32 deterministic bytes derived from ``label``."""
    return hashlib.sha256(label.encode("ascii")).digest()


def build(initiator: bool, static_priv: bytes, remote_pub: bytes, eph_priv: bytes,
          prologue: bytes, psk: bytes) -> NoiseConnection:
    conn = NoiseConnection.from_name(SUITE.pattern_name)
    if initiator:
        conn.set_as_initiator()
    else:
        conn.set_as_responder()
    conn.set_keypair_from_private_bytes(Keypair.STATIC, static_priv)
    conn.set_keypair_from_public_bytes(Keypair.REMOTE_STATIC, remote_pub)
    conn.set_keypair_from_private_bytes(Keypair.EPHEMERAL, eph_priv)
    conn.set_prologue(prologue)
    conn.set_psks(psks=[psk])
    conn.start_handshake()
    return conn


def main() -> None:
    client = Identity.from_private_bytes(fixed("massdroid-test-client-static"))
    server = Identity.from_private_bytes(fixed("massdroid-test-server-static"))
    client_eph = fixed("massdroid-test-client-ephemeral")
    server_eph = fixed("massdroid-test-server-ephemeral")
    psk = fixed("massdroid-test-long-term-psk")

    client_init = json.dumps(
        {"type": "client/init",
         "payload": {"client_id": client.peer_id, "version": 1, "suite": SUITE.value}},
        separators=(",", ":"),
    )
    server_init = json.dumps(
        {"type": "server/init", "payload": {"server_id": server.peer_id, "version": 1}},
        separators=(",", ":"),
    )
    prologue = client_init.encode("utf-8") + server_init.encode("utf-8")

    srv = build(True, server.private_bytes, client.public_bytes, server_eph, prologue, psk)
    cli = build(False, client.private_bytes, server.public_bytes, client_eph, prologue,
                bytes(32))

    msg1_payload = json.dumps({"psk_id": psk_id_for(psk)}, separators=(",", ":")).encode()
    msg1 = bytes(srv.write_message(msg1_payload))
    assert bytes(cli.read_message(msg1)) == msg1_payload
    cli.set_psks(psks=[psk])
    msg2 = bytes(cli.write_message(b"{}"))
    assert bytes(srv.read_message(msg2)) == b"{}"
    assert srv.handshake_finished and cli.handshake_finished
    assert srv.get_handshake_hash() == cli.get_handshake_hash()

    server_hello = b"\x00" + b'{"type":"server/hello","payload":{"name":"Music Assistant"}}'
    audio = bytes([4]) + bytes(i % 251 for i in range(100))
    client_hello = b"\x00" + b'{"type":"client/hello","payload":{"name":"MassDroid"}}'
    big = bytes([4]) + bytes(i % 251 for i in range(70_000))

    server_frames = [bytes(srv.encrypt(server_hello)), bytes(srv.encrypt(audio))]
    client_frame = bytes(cli.encrypt(client_hello))
    fragments = _fragment(big)
    fragment_frames = [bytes(cli.encrypt(f)) for f in fragments]
    for frame in [client_frame, *fragment_frames]:
        srv.decrypt(frame)
    for frame in server_frames:
        cli.decrypt(frame)

    pairing_psk = fixed("massdroid-test-pairing-psk")
    client_eph2 = fixed("massdroid-test-client-ephemeral-2")
    server_eph2 = fixed("massdroid-test-server-ephemeral-2")
    prologue2 = srv.get_handshake_hash()
    srv2 = build(True, server.private_bytes, client.public_bytes, server_eph2, prologue2,
                 pairing_psk)
    cli2 = build(False, client.private_bytes, server.public_bytes, client_eph2, prologue2,
                 bytes(32))

    def handshake_text(noise_bytes: bytes) -> bytes:
        return json.dumps(
            {"type": "noise/handshake", "payload": {"data": b64url_encode(noise_bytes)}},
            separators=(",", ":"),
        ).encode()

    rh_msg1_payload = json.dumps({"psk_id": psk_id_for(pairing_psk)}, separators=(",", ":"))
    rh_msg1 = bytes(srv2.write_message(rh_msg1_payload.encode()))
    rh_server_frame = bytes(srv.encrypt(b"\x00" + handshake_text(rh_msg1)))
    assert bytes(cli.decrypt(rh_server_frame))[1:] == handshake_text(rh_msg1)
    cli2.read_message(rh_msg1)
    cli2.set_psks(psks=[pairing_psk])
    rh_msg2 = bytes(cli2.write_message(b"{}"))
    rh_client_text = handshake_text(rh_msg2)
    rh_client_frame = bytes(cli.encrypt(b"\x00" + rh_client_text))
    assert bytes(srv.decrypt(rh_client_frame))[1:] == rh_client_text
    assert bytes(srv2.read_message(rh_msg2)) == b"{}"
    assert srv2.get_handshake_hash() == cli2.get_handshake_hash()

    post_server_frame = bytes(srv2.encrypt(server_hello))
    post_client_frame = bytes(cli2.encrypt(client_hello))
    assert bytes(srv2.decrypt(post_client_frame)) == client_hello

    pairing_token = encode_token(PSKPairingToken(client.peer_id, pairing_psk))
    assert decode_token(pairing_token).pairing_psk == pairing_psk

    out = {
        "suite": SUITE.value,
        "client_static_private": b64url_encode(client.private_bytes),
        "client_static_public": client.peer_id,
        "server_static_public": server.peer_id,
        "client_ephemeral_private": b64url_encode(client_eph),
        "psk": b64url_encode(psk),
        "psk_id": psk_id_for(psk),
        "sentinel_psk": b64url_encode(SENTINEL_PSK),
        "sentinel_psk_id": psk_id_for(SENTINEL_PSK),
        "client_init": client_init,
        "server_init": server_init,
        "msg1": b64url_encode(msg1),
        "msg1_payload": msg1_payload.decode(),
        "msg2": b64url_encode(msg2),
        "handshake_hash": b64url_encode(srv.get_handshake_hash()),
        "server_frames": [b64url_encode(f) for f in server_frames],
        "server_plaintexts": [b64url_encode(server_hello), b64url_encode(audio)],
        "client_plaintext": b64url_encode(client_hello),
        "client_frame": b64url_encode(client_frame),
        "fragment_plaintext_length": len(big),
        "fragment_frame_sizes": [len(f) for f in fragment_frames],
        "fragment_frame_sha256": [hashlib.sha256(f).hexdigest() for f in fragment_frames],
        "pairing_psk": b64url_encode(pairing_psk),
        "client_ephemeral2_private": b64url_encode(client_eph2),
        "rehandshake_server_frame": b64url_encode(rh_server_frame),
        "rehandshake_client_text": rh_client_text.decode(),
        "rehandshake_client_frame": b64url_encode(rh_client_frame),
        "rehandshake_hash": b64url_encode(srv2.get_handshake_hash()),
        "post_rehandshake_server_frame": b64url_encode(post_server_frame),
        "post_rehandshake_client_frame": b64url_encode(post_client_frame),
        "pairing_token": pairing_token,
    }
    print(json.dumps(out, indent=2))


main()
