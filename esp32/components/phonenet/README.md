# phonenet (ESP-IDF component)

Adds an lwIP network interface `pn` (`10.99.0.2/24`, gateway `10.99.0.1`) whose packets are relayed as UDP frames to the reverse-net Android app. See [docs/protocol.md](../../../docs/protocol.md).

```c
#include "phonenet.h"

wifi_init_softap();                 /* AP on 192.168.4.1 */
phonenet_start();
while (!phonenet_is_up()) vTaskDelay(pdMS_TO_TICKS(500));
```

- Listens on UDP port `5000`; one peer at a time.
- Becomes the default netif and sets DNS to `8.8.8.8` while the link is up.
- Requires ESP-IDF 5.x. Not yet compiled or run on hardware: check lwIP function names against your version.
- TCP and HTTPS work through the Android app's TCP NAT; the ESP32 needs enough heap and stack for TLS (see the example's `sdkconfig.defaults`).
