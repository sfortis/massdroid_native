"""Generate golden vectors for the MassDroid Sendspin Noise unit tests, Sendspin 1.0.0-rc1 wire.

These vectors describe the wire of aiosendspin 10, which Music Assistant 2.10.7, 2.10.8 and
2.11.0b4 and later ship. The legacy wire of aiosendspin 9.1.1 has its own frozen vectors,
`noise_vectors_9_1_1.json`. Three things differ from those:

- Handshake message 1 carries `psk_category` beside `psk_id`. Because message 1's ciphertext
  enters the handshake hash, message 2, the hash and every later frame differ too.
- Fragments use type 1 with a flags byte instead of types 2 and 3.
- The pairing token functions are `encode_psk_token` and `decode_psk_token`.

The vectors also cover a single complete fragment frame (flags 0x03), server fragments of a large
binary message for the client to reassemble, and the Sentinel fallback: the server names a
long-term PSK the client does not hold, the client answers message 2 under the Sentinel PSK, and
the server accepts it by replaying message 1 under the Sentinel PSK, as aiosendspin's
`NoiseSession.fork_at_message_2` does.

The server side plays the Noise initiator exactly as aiosendspin.noise.session._build_conn does,
with one difference: the ephemeral keys are fixed so the output is deterministic. MassDroid is
the responder. Run it inside the MA container, where aiosendspin 10 is installed. It only reads:

    ssh mass 'docker exec -i music-assistant-server python3 -' < core/src/test/resources/sendspin/generate_noise_vectors_rc1.py \
        > core/src/test/resources/sendspin/noise_vectors_rc1.json
"""

from __future__ import annotations

import hashlib
import importlib.metadata
import json

from aiosendspin.noise.constants import SENTINEL_PSK
from aiosendspin.noise.keys import Identity, b64url_encode, psk_id_for
from aiosendspin.noise.models import NoiseMsg1Payload
from aiosendspin.noise.pairing_token import PSKPairingToken, decode_psk_token, encode_psk_token
from aiosendspin.noise.session import NoiseCipherSuite
from aiosendspin.noise.trust_store import PskCategory
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


def msg1_payload_for(psk: bytes, category: PskCategory) -> bytes:
    """Return message 1's plaintext as aiosendspin.noise.driver builds it."""
    payload = NoiseMsg1Payload(psk_id=psk_id_for(psk), psk_category=category.code)
    return payload.to_json().encode("utf-8")


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

    msg1_payload = msg1_payload_for(psk, PskCategory.LONG_TERM)
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
    server_fragment_frames = [bytes(srv.encrypt(f)) for f in _fragment(big)]
    single = _fragment(audio)
    assert len(single) == 1 and single[0][1] == 0x03
    single_fragment_frame = bytes(srv.encrypt(single[0]))
    client_frame = bytes(cli.encrypt(client_hello))
    fragments = _fragment(big)
    fragment_frames = [bytes(cli.encrypt(f)) for f in fragments]
    for frame in [client_frame, *fragment_frames]:
        srv.decrypt(frame)
    for frame in [*server_frames, *server_fragment_frames, single_fragment_frame]:
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

    rh_msg1_payload = msg1_payload_for(pairing_psk, PskCategory.PAIRING)
    rh_msg1 = bytes(srv2.write_message(rh_msg1_payload))
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

    pairing_token = encode_psk_token(PSKPairingToken(client.peer_id, pairing_psk))
    assert decode_psk_token(pairing_token).pairing_psk == pairing_psk

    # The Sentinel fallback, on a fresh connection with the same prologue. The server names a
    # long-term PSK the client does not hold, so the client answers under the Sentinel PSK.
    unknown_psk = fixed("massdroid-test-unknown-long-term-psk")
    client_eph3 = fixed("massdroid-test-client-ephemeral-3")
    server_eph3 = fixed("massdroid-test-server-ephemeral-3")
    srv3 = build(True, server.private_bytes, client.public_bytes, server_eph3, prologue, unknown_psk)
    cli3 = build(False, client.private_bytes, server.public_bytes, client_eph3, prologue, bytes(32))
    sn_msg1_payload = msg1_payload_for(unknown_psk, PskCategory.LONG_TERM)
    sn_msg1 = bytes(srv3.write_message(sn_msg1_payload))
    assert bytes(cli3.read_message(sn_msg1)) == sn_msg1_payload
    cli3.set_psks(psks=[SENTINEL_PSK])
    sn_msg2 = bytes(cli3.write_message(b"{}"))
    # fork_at_message_2: replay message 1 on the same ephemeral, keyed by the Sentinel PSK.
    fork = build(True, server.private_bytes, client.public_bytes, server_eph3, prologue, SENTINEL_PSK)
    assert bytes(fork.write_message(sn_msg1_payload)) == sn_msg1
    assert bytes(fork.read_message(sn_msg2)) == b"{}"
    assert fork.get_handshake_hash() == cli3.get_handshake_hash()

    out = {
        "aiosendspin": importlib.metadata.version("aiosendspin"),
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
        "server_fragment_frames": [b64url_encode(f) for f in server_fragment_frames],
        "single_fragment_frame": b64url_encode(single_fragment_frame),
        "pairing_psk": b64url_encode(pairing_psk),
        "client_ephemeral2_private": b64url_encode(client_eph2),
        "rehandshake_server_frame": b64url_encode(rh_server_frame),
        "rehandshake_client_text": rh_client_text.decode(),
        "rehandshake_client_frame": b64url_encode(rh_client_frame),
        "rehandshake_hash": b64url_encode(srv2.get_handshake_hash()),
        "post_rehandshake_server_frame": b64url_encode(post_server_frame),
        "post_rehandshake_client_frame": b64url_encode(post_client_frame),
        "pairing_token": pairing_token,
        "rehandshake_msg1_payload": rh_msg1_payload.decode(),
        "psk_category_codes": {c.name: c.code for c in PskCategory},
        "sentinel_fallback": {
            "unknown_psk": b64url_encode(unknown_psk),
            "client_ephemeral_private": b64url_encode(client_eph3),
            "msg1": b64url_encode(sn_msg1),
            "msg1_payload": sn_msg1_payload.decode(),
            "msg2": b64url_encode(sn_msg2),
            "handshake_hash": b64url_encode(fork.get_handshake_hash()),
        },
    }
    print(json.dumps(out, indent=2))


main()
