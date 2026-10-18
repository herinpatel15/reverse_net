# Example: HTTP and HTTPS over the phone

This shows how to add reverse-net to your own ESP-IDF project. For a complete, buildable project, see [`esp32/examples/basic`](../../esp32/examples/basic).

## 1. Add the component

Copy the component into your project, or point ESP-IDF at this repository:

```
my_project/
  CMakeLists.txt
  main/
    main.c
  components/
    phonenet/          <- copy of esp32/components/phonenet
```

Or, without copying, set this in your top-level `CMakeLists.txt` before `project()`:

```cmake
set(EXTRA_COMPONENT_DIRS "/path/to/reverse_net/esp32/components")
```

## 2. Declare the dependency

In `main/CMakeLists.txt`:

```cmake
idf_component_register(SRCS "main.c"
                       REQUIRES phonenet esp_wifi esp_netif esp_event nvs_flash esp_http_client esp-tls mbedtls)
```

For HTTPS, use the certificate bundle and a larger main task stack. Put this in `sdkconfig.defaults`:

```
CONFIG_ESP_MAIN_TASK_STACK_SIZE=8192
CONFIG_MBEDTLS_CERTIFICATE_BUNDLE=y
CONFIG_ESP_TLS_USING_MBEDTLS=y
```

## 3. Start the access point, then the relay

Your Wi-Fi access point must be running before `phonenet_start()`. The ESP32 must be at `192.168.4.1` (the ESP-IDF SoftAP default).

```c
#include "phonenet.h"

void app_main(void)
{
    /* ... nvs_flash_init() and your SoftAP setup, as in esp32/examples/basic ... */

    ESP_ERROR_CHECK(phonenet_start());

    while (!phonenet_is_up()) {          /* wait for the phone's HELLO */
        vTaskDelay(pdMS_TO_TICKS(500));
    }

    /* From here on, normal networking works through the phone. */
}
```

`phonenet_start()` returns an error if the UDP socket cannot be opened on port 5000. Check it.

## 4. Make requests as usual

```c
#include <string.h>
#include "esp_crt_bundle.h"
#include "esp_http_client.h"
#include "esp_log.h"

static void get(const char *url)
{
    esp_http_client_config_t cfg = { .url = url };
    if (strncmp(url, "https", 5) == 0) {
        cfg.crt_bundle_attach = esp_crt_bundle_attach;   /* needs CONFIG_MBEDTLS_CERTIFICATE_BUNDLE */
    }
    esp_http_client_handle_t c = esp_http_client_init(&cfg);
    esp_err_t err = esp_http_client_perform(c);
    ESP_LOGI("app", "%s -> %s, status %d", url, esp_err_to_name(err),
             esp_http_client_get_status_code(c));
    esp_http_client_cleanup(c);
}

/* in app_main, after phonenet_is_up() */
get("http://example.com/");
get("https://example.com/");
```

## What works

| Feature | Status |
| --- | --- |
| DNS lookups | Works through the phone's UDP NAT |
| ICMP ping to `10.99.0.1` | Answered by the app |
| Plain HTTP | Through the TCP NAT (see [docs/tcp.md](../../docs/tcp.md)) |
| HTTPS | Works; TLS runs on the ESP32, so it needs about 40 KB of free heap |
| Other devices on the ESP32 Wi-Fi | No internet; the ESP32 talks to one phone only |

## If it does not work

- Stuck at "waiting for the Android relay": check the phone is joined to the ESP32 Wi-Fi and the app is started. See [docs/troubleshooting.md](../../docs/troubleshooting.md).
- HTTP times out: look for `tcp connect ... failed` in the phone's logcat (`adb logcat -s ReverseNet`).
- HTTPS fails: check free heap with `esp_get_free_heap_size()`.
