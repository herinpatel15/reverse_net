# 3. ESP32 track: step by step

Language: C. Framework: ESP-IDF 5.x. Final result: an ESP-IDF component `phonenet` with two functions, `phonenet_start()` and `phonenet_is_up()`.

Reference solution: [`esp32/components/phonenet/phonenet.c`](../esp32/components/phonenet/phonenet.c). Do not open it until your step's test has passed or you have been stuck for an hour.

---

## Step E1: Project and SoftAP

**Goal:** the ESP32 runs a Wi-Fi access point; the phone can join and reach `192.168.4.1`.

1. Copy ESP-IDF's `wifi/getting_started/softap` example into `esp32/examples/basic`, or create a new project with `idf.py create-project`.
2. Read the example and understand each call: `esp_netif_init`, `esp_event_loop_create_default`, `esp_netif_create_default_wifi_ap`, `esp_wifi_init`, `esp_wifi_set_config`, `esp_wifi_start`.
3. Put SSID and password in `menuconfig` (`Kconfig.projbuild`) instead of hard-coding them.
4. Use WPA2 and a password of at least 8 characters.

**Test:** flash. On the phone join the network. Serial log shows a station connected.
**Common mistakes:** forgetting `nvs_flash_init()`; password shorter than 8 chars (the AP silently falls back or fails).

Commit: `esp32: softap example`.

---

## Step E2: Component skeleton

**Goal:** a component that builds and prints a line.

1. Create `esp32/components/phonenet/` with `CMakeLists.txt`, `include/phonenet.h`, `phonenet.c`.
2. `CMakeLists.txt`: `idf_component_register(SRCS "phonenet.c" INCLUDE_DIRS "include" REQUIRES lwip esp_timer)`.
3. In the example's `CMakeLists.txt` set `EXTRA_COMPONENT_DIRS` to `../../components`.
4. In `phonenet.h` declare `esp_err_t phonenet_start(void); bool phonenet_is_up(void);`. Implement stubs.
5. Call `phonenet_start()` after the AP starts and log from it.

**Test:** the stub's log line appears on serial.

---

## Step E3: UDP socket and receive task

**Goal:** the ESP32 receives datagrams on port 5000 and logs their bytes.

1. In `phonenet_start`: `socket(AF_INET, SOCK_DGRAM, 0)`, `bind` to `INADDR_ANY:5000`, set `SO_RCVTIMEO` to 1 s.
2. Start a FreeRTOS task (`xTaskCreate`, stack 4096, priority 5) that loops on `recvfrom`.
3. Log the length and source address of each datagram.
4. Use `static` for the big receive buffer so it does not live on the task stack.

**Test:** from a computer joined to the AP run `python3 -c "import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.sendto(b'hi',('192.168.4.1',5000))"`. The serial log shows 2 bytes.
**Common mistakes:** forgetting the timeout, so the task cannot do periodic work; wrong byte order of the port (`htons`).

---

## Step E4: Frame parsing

**Goal:** the task validates frames and dispatches by type.

1. Check `n >= 3` and `(rx[1] << 8 | rx[2]) == n - 3`. Drop otherwise.
2. Switch on `rx[0]`. For now only log the type.
3. Write the constants (`T_HELLO` etc.) exactly as in the protocol doc.

**Test:** send crafted frames from Python using `tools/rn_protocol.py`: a valid one logs; one with a wrong length is silently dropped.

---

## Step E5: Handshake and liveness

**Goal:** the ESP32 prints `link up` when the app sends HELLO.

1. State: `s_peer` (a `sockaddr_in`), `s_up` (`volatile bool`), `s_last_rx_us`.
2. On HELLO (type `0x01`, length at least 6, version 1) while the link is down: store the sender as the peer, send HELLO_ACK `{1, 0x05, 0x78}`, set `s_up = true`, log `link up`.
3. Accept later frames only if `from` equals the stored peer (address and port).
4. Every 5 s send KEEPALIVE. If no frame for 15 s, set `s_up = false` and log `link down`.
5. Handle CLOSE: link down.
6. Decide what a repeated HELLO does while up (answer again if it is from the same IP; ignore other IPs).

**Test:** run `tools/pc_relay.py` on a computer joined to the AP. Serial shows `link up`; stop the script with Ctrl+C and serial shows `link closed`; kill it with `kill -9` and after 15 s serial shows `link down`.
**Common mistakes:** comparing `sockaddr` structs with `memcmp` (padding differs) instead of fields; sending ACK before storing the peer.

Commit: `esp32: handshake and liveness`. This completes **milestone 2** for the ESP32 half.

---

## Step E6: Understand lwIP before writing netif code

Spend an hour here. You need to know:
- A `struct netif` is a network card. lwIP calls its `output` function when it wants to send an IP packet.
- To inject a received packet, allocate a `pbuf`, copy bytes in, and call `netif->input(pbuf, netif)`. With `tcpip_input` as the input function, the packet is passed to the lwIP thread safely.
- lwIP is not thread-safe from your task: configuration calls (`netif_add`, `netif_set_default`, `netif_set_link_up`) must run in the tcpip thread via `tcpip_callback`.
- lwIP picks the outgoing interface with a route table: longest-matching subnet, else the default netif.

Read the lwIP docs for `netif_add`, `netif_set_default`, `pbuf_alloc`, `pbuf_take`, `pbuf_copy_partial`.

---

## Step E7: Register the network interface

**Goal:** an interface named `pn` exists with IP `10.99.0.2`.

1. Write `netif_init_cb(struct netif*)`: set `name = "pn"`, `mtu = 1400`, `output = pn_output`.
2. Write `add_netif_cb` that does `netif_add(&s_netif, &ip, &mask, &gw, NULL, netif_init_cb, tcpip_input)`, `netif_set_up`, `netif_set_link_down`.
3. Run it with `tcpip_callback(add_netif_cb, NULL)` from `phonenet_start`, and check the return code.
4. Stub `pn_output` to just log the packet length and return `ERR_OK`.
5. `s_netif` must be a static variable; lwIP keeps a pointer to it.

**Test:** at boot nothing crashes. (Optionally call `netif_list` printing to see `pn`.)

---

## Step E8: Make it the default route when the link is up

**Goal:** traffic to the internet goes to `pn`; traffic to the AP subnet does not.

1. When `link up`: `dns_setserver(0, 8.8.8.8)`, `netif_set_default(&s_netif)`, `netif_set_link_up(&s_netif)`. When down: `netif_set_link_down`.
2. Do it all inside a `tcpip_callback` function (`set_link_cb`).
3. Set `s_up` first so `pn_output` knows the state.

**Test:** with the link up call `esp_http_client` or `ping` to some public IP. The stub `pn_output` log now shows outgoing packets (you will see the DNS query for a hostname, or the first TCP SYN).
**Why the AP frames do not loop:** `192.168.4.x` matches the AP interface subnet exactly, so lwIP never routes them to `pn`.

---

## Step E9: Output path (ESP32 -> phone)

**Goal:** every packet leaving `pn` becomes an IP_PACKET frame.

1. In `pn_output`: if `!s_up` or `tot_len > MTU` return `ERR_IF`.
2. Build `tx[0]=0x10`, `tx[1..2]=tot_len BE`, copy with `pbuf_copy_partial(p, tx+3, p->tot_len, 0)`.
3. `sendto` to `s_peer`.
4. Make `tx` a `static` buffer: the tcpip thread stack is small.

**Test:** run `pc_relay.py`; ping from the ESP32 (or any outgoing traffic) and the script prints `ip proto=... 10.99.0.2 -> ...`.

---

## Step E10: Input path (phone -> ESP32)

**Goal:** IP_PACKET frames received are delivered to lwIP.

1. On a type `0x10` frame from the peer: `pbuf_alloc(PBUF_RAW, len, PBUF_POOL)`, `pbuf_take`, `s_netif.input(pb, &s_netif)`.
2. If `input` returns an error, `pbuf_free(pb)`. If `pbuf_alloc` returns NULL, drop the packet.

**Test (needs the ping answer from the app side, see the Android guide A5):** `ping` from the ESP32 to `10.99.0.1` succeeds. Use `esp_ping` or the console `ping` command.

Commit: `esp32: netif in/out`. This completes **milestone 4** for the ESP32 half.

---

## Step E11: Hardening

Do these once everything above works:
- Check every return value (`tcpip_callback`, `xTaskCreate`, `bind`).
- Free the socket if `bind` fails.
- Log `esp_get_free_heap_size()` during a large download.
- Try MTU 1300 if big transfers stall.
- Add a Kconfig option for the port and MTU (optional).
- Write the example project (`app_main`: NVS init, SoftAP, `phonenet_start`, wait for `phonenet_is_up`, HTTP GET).

## Step E12: ESP32 definition of done

- [ ] `link up`, `link down` logs correct on disconnect and reconnect
- [ ] ping to `10.99.0.1` works
- [ ] DNS lookup works through the phone
- [ ] no crashes after 10 minutes with keepalives
- [ ] the component README explains usage
