# Phonenet: ESP32 internet relay guide

Oct 18, 2026 · @herin pate

This guide builds one thing: an ESP32 that gets internet through an Android phone connected to its Wi-Fi. It works only with your own Android app, not with other Wi-Fi devices. The ESP32 side is quick; the phone-side NAT is the hard part.

## What you are building

The ESP32 gets a virtual network card. Everything it sends to the internet (HTTP, MQTT, DNS) goes into that card as normal IP packets. Your library wraps each packet in a small frame and sends it over the ESP32's own Wi-Fi (AP mode) to your Android app. The app sends it to the internet over mobile data.

1. The ESP32 app opens a connection as usual. lwIP builds an IP packet.
2. The `phonenet` network interface wraps the packet in a frame and sends it by UDP to the Android app.
3. The app unwraps the packet, opens a real socket on the mobile network, and forwards the data.
4. The reply returns the same way, and lwIP hands it to your app as if the ESP32 had real internet.

&#91;embedded content: data path · request out, reply back\]

The solid arrows are the request going out: the ESP32 wraps each packet in a frame, the phone unwraps it, rebuilds the connection on mobile data and forwards it. The dashed arrows are the reply coming back the same way.

Two parts share one protocol:

- **ESP32 library** (C): adds the virtual network interface and talks to the phone.
- **Android app** (Kotlin): Android cannot do kernel NAT, so the app must rebuild every TCP and UDP connection itself. This is the hard part.

**Not universal.** This is a private link, not a hotspot. Only your Android app can use it. Other phones or laptops that join the ESP32 Wi-Fi get no internet, and the ESP32 ignores their frames.

## What to learn

Learn these in order of need. Times are rough estimates for someone who already codes in C and Python.

| Topic | What you must know | Used in | Time |
| --- | --- | --- | --- |
| IPv4, UDP, TCP headers | Fields, 20-byte IP header, checksums, MTU | Both parts | 2 h |
| UDP sockets in C and Kotlin | Send, receive, timeouts | Both parts | 1 h |
| ESP-IDF basics | Wi-Fi AP mode, FreeRTOS tasks, components | ESP32 library | 2 h |
| lwIP netif | `netif_add`, `output` callback, `tcpip_input`, default route | ESP32 library | 3 h |
| Android foreground service | Service lifecycle, notification, permissions | Android app | 2 h |
| Android `ConnectivityManager` | `requestNetwork`, `bindSocket`, cellular vs Wi-Fi | Android app | 2 h |
| Logging and packet dumps | `logcat`, ESP32 serial log, hex dump of frames | Debugging | 1 h |
| User-space TCP/IP stack | TCP state machine, sequence numbers, or reuse an existing stack | Android app | days |

Best free references: the lwIP documentation, the ESP-IDF programming guide (Networking section), and the Android developer docs for `ConnectivityManager`. I did not look these pages up while writing, so check names and signatures against your installed versions.

## Plan for today

Test each step before starting the next one. If a step fails, do not move on. Steps 1 to 4 are realistic for today; the rest depend on how far the NAT gets.

- [ ] 1\. ESP32 runs in AP mode and the phone joins its Wi-Fi (`192.168.4.1` is reachable from the phone)
- [ ] 2\. ESP32 library built; the Android app sends HELLO and the ESP32 log shows `link up`
- [ ] 3\. The app has both networks: Wi-Fi to the ESP32 and cellular for the internet (shown in its notification)
- [ ] 4\. The ESP32 pings `10.99.0.1` and the app answers
- [ ] 5\. DNS works: the ESP32 resolves a hostname through the app (UDP flows)
- [ ] 6\. TCP works: the ESP32 fetches a plain HTTP page
- [ ] 7\. HTTPS works: the ESP32 completes a TLS request
- [ ] 8\. Reconnect works: restart the app or the ESP32 and the link recovers by itself

## Protocol

Every UDP datagram carries one frame: a 3-byte header and a payload. Write this down first and keep it identical on both sides.

```
+----------+-----------------+----------------------+
| type (1) | length (2, BE)  | payload (length B)   |
+----------+-----------------+----------------------+
```

| Type | Name | Payload | Sent by |
| --- | --- | --- | --- |
| `0x01` | HELLO | version (1 B), tunnel MTU (2 B, BE) | Android app |
| `0x02` | HELLO\_ACK | version (1 B), tunnel MTU (2 B, BE) | ESP32 |
| `0x03` | KEEPALIVE | empty | Both |
| `0x10` | IP\_PACKET | one raw IPv4 packet | Both |
| `0x7F` | CLOSE | empty | Both |

**Addresses and constants**

- ESP32 AP address: `192.168.4.1`, UDP port `5000`.
- Tunnel network: `10.99.0.0/24`. ESP32 is `10.99.0.2`, the Android app is `10.99.0.1`.
- Tunnel MTU: `1400`, so the largest datagram is 1403 bytes and fits one Wi-Fi packet.
- Protocol version: `1`. Drop frames with an unknown version or type.

**Handshake and liveness**

1. The phone joins the ESP32 Wi-Fi and the Android app sends HELLO to `192.168.4.1:5000`.
2. The ESP32 stores the sender's address as its peer and answers HELLO\_ACK. From now on it sends IP\_PACKET frames to that peer.
3. Both sides send KEEPALIVE every 5 s. A side that hears nothing for 15 s marks the link down and waits for a new HELLO.
4. The ESP32 accepts frames only from its current peer address, so a second device on the AP cannot inject packets.

There is no encryption or authentication in version 1. Anyone who joins the ESP32 Wi-Fi can send frames to the ESP32 while the link is down, so use a strong AP password and add a shared-key check before real use.

## Step 1: ESP32 library

Build this as an ESP-IDF component named `phonenet`. It adds one lwIP network interface (`pn`) with IP `10.99.0.2` and gateway `10.99.0.1`, and a task that moves frames between a UDP socket and that interface. Your app keeps using normal sockets, `esp_http_client` and MQTT.

```
components/phonenet/
  CMakeLists.txt
  include/phonenet.h
  phonenet.c
```

**CMakeLists.txt**

```cmake
idf_component_register(SRCS "phonenet.c"
                       INCLUDE_DIRS "include"
                       REQUIRES lwip esp_timer)
```

**include/phonenet.h**

```c
#pragma once
#include <stdbool.h>
#include "esp_err.h"

esp_err_t phonenet_start(void);   // call after the Wi-Fi AP is running
bool phonenet_is_up(void);        // true while the relay is connected
```

**phonenet.c**

```c
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lwip/dns.h"
#include "lwip/ip4_addr.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/sockets.h"
#include "lwip/tcpip.h"
#include "phonenet.h"

#define PN_PORT 5000
#define PN_MTU 1400
#define PN_VERSION 1
#define T_HELLO 0x01
#define T_HELLO_ACK 0x02
#define T_KEEPALIVE 0x03
#define T_IP 0x10
#define T_CLOSE 0x7F

static const char *TAG = "phonenet";
static struct netif s_netif;
static int s_sock = -1;
static struct sockaddr_in s_peer;
static volatile bool s_up = false;
static int64_t s_last_rx_us = 0;

static void send_ctrl(uint8_t type, const uint8_t *payload, uint16_t len)
{
    uint8_t buf[3 + 4];
    buf[0] = type;
    buf[1] = len >> 8;
    buf[2] = len & 0xFF;
    if (len) memcpy(buf + 3, payload, len);
    sendto(s_sock, buf, 3 + len, 0, (struct sockaddr *)&s_peer, sizeof(s_peer));
}

/* lwIP calls this for every packet leaving the pn interface (tcpip thread). */
static err_t pn_output(struct netif *n, struct pbuf *p, const ip4_addr_t *ip)
{
    static uint8_t tx[3 + PN_MTU];   /* static: tcpip thread stack is small */
    if (!s_up || p->tot_len > PN_MTU) return ERR_IF;
    tx[0] = T_IP;
    tx[1] = p->tot_len >> 8;
    tx[2] = p->tot_len & 0xFF;
    pbuf_copy_partial(p, tx + 3, p->tot_len, 0);
    sendto(s_sock, tx, 3 + p->tot_len, 0, (struct sockaddr *)&s_peer, sizeof(s_peer));
    return ERR_OK;
}

static err_t netif_init_cb(struct netif *n)
{
    n->name[0] = 'p';
    n->name[1] = 'n';
    n->output = pn_output;
    n->mtu = PN_MTU;
    return ERR_OK;
}

static void add_netif_cb(void *arg)
{
    ip4_addr_t ip, mask, gw;
    IP4_ADDR(&ip, 10, 99, 0, 2);
    IP4_ADDR(&mask, 255, 255, 255, 0);
    IP4_ADDR(&gw, 10, 99, 0, 1);
    netif_add(&s_netif, &ip, &mask, &gw, NULL, netif_init_cb, tcpip_input);
    netif_set_up(&s_netif);
    netif_set_link_down(&s_netif);
}

static void set_link_cb(void *arg)
{
    if ((bool)(uintptr_t)arg) {
        ip_addr_t dns;
        IP_ADDR4(&dns, 8, 8, 8, 8);
        dns_setserver(0, &dns);
        netif_set_default(&s_netif);
        netif_set_link_up(&s_netif);
    } else {
        netif_set_link_down(&s_netif);
    }
}

static void set_link(bool up)
{
    s_up = up;
    tcpip_callback(set_link_cb, (void *)(uintptr_t)up);
}

static void pn_task(void *arg)
{
    static uint8_t rx[3 + PN_MTU + 16];
    int64_t last_ka_us = 0;
    for (;;) {
        struct sockaddr_in from;
        socklen_t flen = sizeof(from);
        int n = recvfrom(s_sock, rx, sizeof(rx), 0, (struct sockaddr *)&from, &flen);
        int64_t now = esp_timer_get_time();
        if (n >= 3 && ((rx[1] << 8) | rx[2]) == n - 3) {
            uint8_t type = rx[0];
            bool from_peer = s_up &&
                from.sin_addr.s_addr == s_peer.sin_addr.s_addr &&
                from.sin_port == s_peer.sin_port;
            if (type == T_HELLO && n >= 6 && rx[3] == PN_VERSION && (!s_up || from_peer)) {
                s_peer = from;
                uint8_t p[3] = { PN_VERSION, PN_MTU >> 8, PN_MTU & 0xFF };
                send_ctrl(T_HELLO_ACK, p, 3);
                s_last_rx_us = now;
                if (!s_up) {
                    set_link(true);
                    ESP_LOGI(TAG, "link up");
                }
            } else if (from_peer) {
                s_last_rx_us = now;
                if (type == T_IP) {
                    struct pbuf *pb = pbuf_alloc(PBUF_RAW, n - 3, PBUF_POOL);
                    if (pb) {
                        pbuf_take(pb, rx + 3, n - 3);
                        if (s_netif.input(pb, &s_netif) != ERR_OK) pbuf_free(pb);
                    }
                } else if (type == T_CLOSE) {
                    set_link(false);
                }
            }
        }
        if (s_up && now - last_ka_us > 5000000) {
            send_ctrl(T_KEEPALIVE, NULL, 0);
            last_ka_us = now;
        }
        if (s_up && now - s_last_rx_us > 15000000) {
            set_link(false);
            ESP_LOGW(TAG, "link down");
        }
    }
}

esp_err_t phonenet_start(void)
{
    tcpip_callback(add_netif_cb, NULL);
    s_sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (s_sock < 0) return ESP_FAIL;
    struct sockaddr_in a = {
        .sin_family = AF_INET,
        .sin_port = htons(PN_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(s_sock, (struct sockaddr *)&a, sizeof(a)) != 0) return ESP_FAIL;
    struct timeval tv = { .tv_sec = 1 };
    setsockopt(s_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    xTaskCreate(pn_task, "phonenet", 4096, NULL, 5, NULL);
    return ESP_OK;
}

bool phonenet_is_up(void) { return s_up; }
```

**Using it in `main`**

```c
wifi_init_softap();      /* your existing AP setup */
phonenet_start();
while (!phonenet_is_up()) vTaskDelay(pdMS_TO_TICKS(500));
/* now call esp_http_client, esp-tls, MQTT as normal */
```

**Why it works.** lwIP picks the route for each packet. Traffic to `192.168.4.x` (the Android app's frames) stays on the AP interface because that subnet matches exactly. Everything else goes to the default interface, which is `pn` once the link is up. That is why the app's frames never loop back into the tunnel.

This is a sketch I have not compiled. Check the lwIP function names against your ESP-IDF version (`tcpip_callback`, `tcpip_input`, `netif_add`), and watch the heap if you move large downloads.

## Step 2: Android app

The phone is the only relay. It joins the ESP32 Wi-Fi, sends HELLO, and moves IP packets between the ESP32 and the internet. Linux could do the NAT in the kernel, but Android apps cannot, so the app must open a real socket for every connection the ESP32 makes and rebuild the packets for the reply. Plan this as several days of work, not part of today.

**Manifest permissions**

- `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`
- `FOREGROUND_SERVICE` (and the matching service type), `POST_NOTIFICATIONS` on Android 13 and later

Run the relay in a foreground service so Android does not kill it when the screen is off. When Android asks whether to stay connected to a Wi-Fi network without internet, answer yes.

**Use both networks on purpose.** The ESP32 link is Wi-Fi without internet, and the real traffic must leave over mobile data. Request each network and bind sockets to it:

```kotlin
private lateinit var cm: ConnectivityManager
@Volatile private var cellular: Network? = null
@Volatile private var wifi: Network? = null

private fun requestNetworks() {
    cm = getSystemService(ConnectivityManager::class.java)
    cm.requestNetwork(
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build(),
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cellular = network }
            override fun onLost(network: Network) { if (cellular == network) cellular = null }
        })
    cm.requestNetwork(
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build(),
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifi = network }
            override fun onLost(network: Network) { if (wifi == network) wifi = null }
        })
}

// Control link to the ESP32 (same frames as the PC relay)
val ctl = DatagramSocket()
wifi?.bindSocket(ctl)
val esp = InetSocketAddress("192.168.4.1", 5000)

// Every outgoing internet socket
val out = DatagramSocket()           // or Socket() for TCP
cellular?.bindSocket(out)
```

If `cellular` stays null, nothing can leave the phone, so show that state in your notification. Whether Android lets the Wi-Fi request match a network with no internet depends on the Android version; test it early on your phone.

**Link loop.** This is the handshake from the Protocol section in Kotlin. Run `linkLoop` on its own thread inside the foreground service.

```kotlin
const val T_HELLO = 0x01
const val T_HELLO_ACK = 0x02
const val T_KEEPALIVE = 0x03
const val T_IP = 0x10
const val T_CLOSE = 0x7F
const val MTU = 1400

fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
    ByteBuffer.allocate(3 + payload.size)
        .put(type.toByte()).putShort(payload.size.toShort()).put(payload).array()

fun parse(data: ByteArray, len: Int): Pair<Int, ByteArray>? {
    if (len < 3) return null
    val type = data[0].toInt() and 0xFF
    val plen = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
    if (len != 3 + plen) return null
    return type to data.copyOfRange(3, len)
}

private fun send(data: ByteArray) = ctl.send(DatagramPacket(data, data.size, esp))

private fun linkLoop() {
    val hello = frame(T_HELLO, byteArrayOf(1, (MTU shr 8).toByte(), (MTU and 0xFF).toByte()))
    val buf = ByteArray(2048)
    ctl.soTimeout = 1000
    var up = false
    var lastRx = 0L
    var lastHello = 0L
    var lastKa = 0L
    while (running) {
        val now = SystemClock.elapsedRealtime()
        if (up && now - lastRx > 15_000) up = false
        if (!up && now - lastHello > 2_000) { send(hello); lastHello = now }
        if (up && now - lastKa > 5_000) { send(frame(T_KEEPALIVE)); lastKa = now }
        val pkt = DatagramPacket(buf, buf.size)
        try { ctl.receive(pkt) } catch (e: SocketTimeoutException) { continue }
        if (pkt.socketAddress != esp) continue
        val (type, payload) = parse(buf, pkt.length) ?: continue
        lastRx = SystemClock.elapsedRealtime()
        when (type) {
            T_HELLO_ACK -> up = true
            T_IP -> if (up) handleIp(payload)
            T_CLOSE -> up = false
        }
    }
}
```

`handleIp` is where the NAT from the steps below plugs in. Until it exists, log the protocol, source, destination and length of each packet to check that the ESP32 is sending.

**Build the NAT in this order** (each step has its own test):

1. **Frames and handshake.** Add the code above: the `frame`, `parse`, HELLO and KEEPALIVE logic in your service. Test: the ESP32 log shows `link up`.
2. **Packet parser.** Read the IPv4 header (version, length, protocol, source, destination) and verify checksums. Write a function that builds an IPv4 packet with a correct header checksum.
3. **ICMP echo to `10.99.0.1`.** Answer pings locally. Test: `ping` from the ESP32 works.
4. **UDP and DNS.** Keep a table keyed by (source port, destination IP, destination port). For a new flow, open a cellular `DatagramSocket`, send the payload, and start a reader. When data returns, build a UDP packet with swapped addresses and a UDP checksum, wrap it in IP\_PACKET and send it to the ESP32. Expire idle flows after 30 s. Test: DNS lookup on the ESP32.
5. **TCP.** One flow per connection with its own state:
   - On SYN, open a cellular `Socket` to the destination. If it connects, reply SYN-ACK with your own sequence number and an MSS option of 1360.
   - Data from the ESP32: write it to the socket and send an ACK.
   - Data from the socket: cut it into segments of at most 1360 bytes, number them, and resend if no ACK arrives within about 1 s.
   - On FIN or RST from either side, close the other side and delete the flow.

**Shortcut for step 5.** TCP is the part that takes days. Existing user-space stacks already do it: the Go library gVisor netstack (used by the `tun2socks` project) and lwIP (used by older `tun2socks` builds) both terminate TCP and give you a socket per flow. You would feed them the packets from the ESP32 instead of from a VPN TUN device, and open the outgoing sockets on the cellular network. Check each project's current API and licence before choosing.

## Test and debug

Check one layer at a time, from the Wi-Fi link up to the internet, and log what each side sees: the ESP32 serial log and `adb logcat` on the phone.

| Problem | Check |
| --- | --- |
| App never reaches `link up` | Is the phone joined to the ESP32 AP? Look for HELLO in the ESP32 serial log, and confirm the control socket is bound to the Wi-Fi `Network`. |
| Link is up but the ESP32 gets no replies | Log every IP packet the app receives (protocol, source, destination, length). If packets arrive, the tunnel works and the problem is the NAT. |
| Packets arrive but nothing leaves the phone | Check that `cellular` is not null and that sockets use `cellular.bindSocket`. `adb shell dumpsys connectivity` lists the active networks. |
| Android drops the ESP32 Wi-Fi | Accept "stay connected" when Android warns about no internet, and keep the Wi-Fi `requestNetwork` active. |
| DNS fails | Query `8.8.8.8` from the ESP32 and log the UDP flow. Check that the reply packet has the swapped addresses and a correct checksum. |
| Checksum errors | Dump the packet as hex in the log and import it into Wireshark (File, Import from Hex Dump); it flags bad IP, UDP and TCP checksums. |
| Small requests work, big downloads stall | MTU problem. Lower `PN_MTU` in the ESP32 and `MTU` in the app to 1300, and the TCP MSS option to 1260. |
| ESP32 crashes or runs out of heap | Check `esp_get_free_heap_size()`, raise the `phonenet` task stack above 4096, and review the lwIP buffer settings in `menuconfig`. |

Add a debug switch in the app that writes every packet as hex to the log. Pasting one into Wireshark shows exactly what the ESP32 sent and what your NAT sent back.

## Limits and risks

- **Not universal.** Only your Android app can relay. Other devices on the ESP32 Wi-Fi (laptops, other phones) get no internet, and the ESP32 talks to one peer only.
- **No security in version 1.** The link is plain UDP with no encryption or login. Use a strong AP password, and add a shared-key signature on each frame before real use.
- **Speed is limited.** Expect roughly a few Mbit/s on the ESP32, shared between the AP link and your traffic. Android adds delay for every packet it rebuilds.
- **Android may fight you.** It can drop a Wi-Fi network that has no internet, or prefer it over mobile data. The explicit `requestNetwork` calls are what avoid this, so test on your real phone model.
- **ICMP and IPv6 are mostly not covered.** The app answers pings to `10.99.0.1` only. IPv4 plus TCP, UDP and DNS is the target.
- **The code here is untested.** The C and Kotlin blocks are sketches I wrote without running them. Expect compile errors and small fixes, especially for your ESP-IDF and Android versions.
