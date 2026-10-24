"""Generate the rc1 client/hello and client/state that the MassDroid wire tests compare against.

The messages are built with aiosendspin 10's own client models, so a test that matches them
proves the app sends exactly what the server's library would send for the same values, and that
nothing in them marks the app as a legacy client. Run it inside the MA container; it only reads:

    ssh mass 'docker exec -i music-assistant-server python3 -' < core/src/test/resources/sendspin/generate_rc1_messages.py \
        > core/src/test/resources/sendspin/rc1_messages.json
"""

from __future__ import annotations

import importlib.metadata
import json

from aiosendspin.models.core import (
    ClientHelloMessage,
    ClientHelloPayload,
    ClientStateMessage,
    ClientStatePayload,
    DeviceInfo,
    PairMethodDescriptor,
    SupportedPairMethods,
    UnpairedAccess,
)
from aiosendspin.models.player import ClientHelloPlayerSupport, PlayerStatePayload, SupportedAudioFormat
from aiosendspin.models.types import AudioCodec, PlayerCommand

FLAC = SupportedAudioFormat(codec=AudioCodec.FLAC, channels=2, sample_rate=48000, bit_depth=16)
OPUS = SupportedAudioFormat(codec=AudioCodec.OPUS, channels=2, sample_rate=48000, bit_depth=16)
PCM = SupportedAudioFormat(codec=AudioCodec.PCM, channels=2, sample_rate=48000, bit_depth=16)

hello = ClientHelloMessage(
    payload=ClientHelloPayload(
        name="MassDroid",
        supported_roles=["player@v1", "metadata@v1"],
        device_info=DeviceInfo(
            product_name="Mobile Application", manufacturer="asksakis.net", software_version="2.40.0"
        ),
        player_support=ClientHelloPlayerSupport(supported_formats=[FLAC, OPUS, PCM], buffer_capacity=4_000_000),
        supported_pair_methods=SupportedPairMethods(pairing_psk=PairMethodDescriptor()),
        unpaired_access=UnpairedAccess(enabled=True),
    )
)
state = ClientStateMessage(
    payload=ClientStatePayload(
        available=True,
        player=PlayerStatePayload(
            volume=40,
            muted=False,
            output_delay_ms=0,
            required_lead_time_ms=250,
            min_buffer_ms=1000,
            supported_commands=[PlayerCommand.VOLUME, PlayerCommand.MUTE],
            format=OPUS,
        ),
    )
)
state_without_player = ClientStateMessage(payload=ClientStatePayload(available=True))

# The server's own reading of each message must carry none of the legacy markers.
parsed = ClientHelloMessage.from_json(hello.to_json()).payload
assert not parsed.trust_level_used and not parsed.legacy_pair_methods_list_used
assert not parsed.legacy_support_keys_used and not parsed.player_support.supported_commands
parsed_state = ClientStateMessage.from_json(state.to_json()).payload
assert not parsed_state.legacy_state_used and not parsed_state.player.legacy_delay_key

print(json.dumps({
    "aiosendspin": importlib.metadata.version("aiosendspin"),
    "hello": json.loads(hello.to_json()),
    "state": json.loads(state.to_json()),
    "state_without_player": json.loads(state_without_player.to_json()),
}, indent=2))
