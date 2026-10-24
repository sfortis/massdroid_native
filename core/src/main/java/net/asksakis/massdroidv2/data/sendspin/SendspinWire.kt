package net.asksakis.massdroidv2.data.sendspin

/**
 * The Sendspin wire one connection speaks. It is chosen once per connection and never changes
 * while the connection lives; a reconnect chooses again.
 *
 * The server decides how it talks to the phone from the phone's `client/hello`: the audio header
 * size, the fragment framing, the re-handshake, pairing and the shape of `server/state` all
 * follow that one decision. The phone therefore has to speak one wire completely, never a mix.
 */
enum class SendspinWire {
    /**
     * The wire before Sendspin 1.0: the plaintext transport of Music Assistant older than
     * 2.10.0, and Noise against aiosendspin 9.1.1 (Music Assistant 2.10.0 to 2.10.6 and
     * 2.11.0b1 to b3).
     */
    LEGACY,

    /**
     * The Sendspin 1.0.0-rc1 wire of aiosendspin 10 (Music Assistant 2.10.7 and later,
     * 2.11.0b4 and later). Recognised by `psk_category` in Noise handshake message 1, which
     * aiosendspin 10 always sends and 9.1.1 never does.
     */
    RC1,
}
