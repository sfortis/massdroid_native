# Configuration

## Server Connection

MassDroid connects to the Music Assistant server itself, not to Home Assistant. Music Assistant
answers on port 8095 over plain http, unless its settings change that.

1. Open MassDroid. On the first launch the phone shows a welcome screen; tap **Get started**.
   Later, the same steps are in **Settings > Connection**.
2. Choose your server. The list shows the server you used last and the servers found on this
   network. If yours is not listed, choose **Other server** and type its address. The app looks
   for Music Assistant while you type and says what it found. Under the address,
   **Connection** has four choices: **MA port** (Music Assistant's own port 8095, the usual
   case), **HTTP** (port 80) and **HTTPS** (port 443) for a reverse proxy, and **Custom** for any
   other port. Writing `https://` or a port in the address picks the matching choice.
3. The chosen server stays at the top with its Music Assistant version. Sign in with **Sign in
   with Home Assistant**, or with a Music Assistant username and password. **Change server**
   goes back to the list.
4. Your players will appear on the Home screen.

The phone, the Android TV app and the Android Automotive app sign in in the same two steps and
find servers on the network the same way. The TV and the car sign in with a username and
password only.

### Music Assistant in Home Assistant

When Music Assistant runs as the Home Assistant app (add-on), it runs on the same machine as Home
Assistant. Enter `homeassistant.local`, or the IP address of your Home Assistant machine, with port
`8095`. Do not enter the Home Assistant address on port 8123: Home Assistant answers there, and it
has no Music Assistant connection on that address. The app says so when you type it, and offers the
Music Assistant address when it finds one.

The **Sign in with Home Assistant** button appears when Music Assistant is linked to Home Assistant,
which the add-on always is. After you sign in, Home Assistant sends your browser back to Music
Assistant's own address, which by default is its address on your home network. This sign-in
therefore works at home. Before it opens the browser, the app checks that this phone can reach
both addresses in the sign-in link, and when one cannot be reached it says which and does not open
a page that cannot load. Away from home, sign in once at home (the saved sign-in then keeps
working), set an External URL in Music Assistant and in Home Assistant that the phone can reach, or
sign in with a Music Assistant username and password.

### Music Assistant in Docker

Enter the IP address of the machine that runs the container, with port `8095`. Sign in with a
Music Assistant username and password. The Home Assistant button appears only when that Music
Assistant has its Home Assistant provider set up.

### Away From Home

These ways work:

- **A VPN** such as Tailscale or WireGuard. Use the same address as at home, or the VPN address of
  the server with port 8095.
- **A reverse proxy with https** in front of Music Assistant, for example
  `https://music.example.com`. The proxy must pass WebSocket connections on `/ws` and `/sendspin`,
  because the app and the phone speaker both use them. With https the port is usually 443.

These do not work:

- **The Home Assistant Cloud (Nabu Casa) address.** It reaches only Home Assistant.
- **The Music Assistant panel inside Home Assistant (ingress).** It needs a Home Assistant browser
  session, which an app does not have.
- **Music Assistant's Remote Access (Remote ID).** It needs WebRTC, which MassDroid does not support
  yet.

For remote access with mTLS, install a client certificate on your device and select it with
**Use a client certificate** under the server list on the first launch, or in Settings > Connection.
The app uses it for both WebSocket and image connections.

### Troubleshooting

- **"This address answered, but not as Music Assistant"** means the address leads to another web
  server, usually Home Assistant. Use the Music Assistant address with port 8095.
- **"This server does not use a secure connection"** means HTTPS is chosen, but the server
  answers plain http. Choose **MA port** or **HTTP**, or remove `https://` from the address.
- **"No Music Assistant answers at this address"** means nothing answered as Music Assistant.
  Check the address and the port (Music Assistant uses 8095 unless you changed it), and that the
  device is on your home network or VPN.
- **"Can't find ..."** means the name could not be looked up. If it ends in `.local`, use the IP
  address of the server instead.
- **The browser turns `http://` into `https://`.** Some browsers upgrade addresses to https by
  themselves, which Music Assistant does not serve on port 8095. Type the full address with
  `http://`, or try the IP address. The app itself does not do this.
- **No server under On this network.** The phone must be on the same network as Music
  Assistant, without a guest network or a VPN in between, and the router must pass multicast (mDNS).
  You can always enter the address by hand.

## Metadata Enrichment

Genres, artist biographies, album descriptions, and release years come from your Music Assistant providers first, and from [MusicBrainz](https://musicbrainz.org/) when a provider has nothing to offer. There is no API key to obtain and nothing to configure: enrichment runs in the background on its own and caches its results on the device.

Progress is shown in **Settings** for as long as there is work outstanding.
