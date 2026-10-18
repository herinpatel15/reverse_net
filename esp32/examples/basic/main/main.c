#include <string.h>
#include "esp_crt_bundle.h"
#include "esp_event.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "phonenet.h"

static const char *TAG = "example";

static void wifi_init_softap(void)
{
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_ap();   /* default AP IP is 192.168.4.1 */

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));

    wifi_config_t ap = { 0 };
    strlcpy((char *)ap.ap.ssid, CONFIG_RN_AP_SSID, sizeof(ap.ap.ssid));
    strlcpy((char *)ap.ap.password, CONFIG_RN_AP_PASSWORD, sizeof(ap.ap.password));
    ap.ap.ssid_len = strlen(CONFIG_RN_AP_SSID);
    ap.ap.max_connection = 2;
    ap.ap.authmode = WIFI_AUTH_WPA2_PSK;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap));
    ESP_ERROR_CHECK(esp_wifi_start());
}

static void fetch(esp_http_client_config_t *cfg)
{
    esp_http_client_handle_t c = esp_http_client_init(cfg);
    esp_err_t e = esp_http_client_perform(c);
    ESP_LOGI(TAG, "GET %s: %s, status %d, %d bytes", cfg->url, esp_err_to_name(e),
             esp_http_client_get_status_code(c), (int)esp_http_client_get_content_length(c));
    esp_http_client_cleanup(c);
}

void app_main(void)
{
    esp_err_t r = nvs_flash_init();
    if (r == ESP_ERR_NVS_NO_FREE_PAGES || r == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        r = nvs_flash_init();
    }
    ESP_ERROR_CHECK(r);

    wifi_init_softap();
    ESP_ERROR_CHECK(phonenet_start());

    ESP_LOGI(TAG, "waiting for the Android relay...");
    while (!phonenet_is_up()) vTaskDelay(pdMS_TO_TICKS(500));

    /* Plain HTTP: DNS (UDP) + TCP through the phone. */
    esp_http_client_config_t http = { .url = "http://example.com/" };
    fetch(&http);

    /* HTTPS: same path plus TLS on the ESP32 (needs ~40 KB of free heap). */
    esp_http_client_config_t https = {
        .url = "https://example.com/",
        .crt_bundle_attach = esp_crt_bundle_attach,
    };
    fetch(&https);
}
