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
#define PN_KEEPALIVE_US 5000000
#define PN_TIMEOUT_US 15000000
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
    if (len > 4) return;
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
    if (tcpip_callback(set_link_cb, (void *)(uintptr_t)up) != ERR_OK) {
        ESP_LOGE(TAG, "tcpip_callback failed");
    }
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
            bool same_ip = s_up && from.sin_addr.s_addr == s_peer.sin_addr.s_addr;
            bool from_peer = same_ip && from.sin_port == s_peer.sin_port;
            /* A HELLO from the peer's IP is accepted even from a new port
             * (the app restarted); anything else is ignored while up. */
            if (type == T_HELLO && n >= 6 && rx[3] == PN_VERSION && (!s_up || same_ip)) {
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
                    ESP_LOGI(TAG, "link closed by peer");
                }
            }
        }
        if (s_up && now - last_ka_us > PN_KEEPALIVE_US) {
            send_ctrl(T_KEEPALIVE, NULL, 0);
            last_ka_us = now;
        }
        if (s_up && now - s_last_rx_us > PN_TIMEOUT_US) {
            set_link(false);
            ESP_LOGW(TAG, "link down");
        }
    }
}

esp_err_t phonenet_start(void)
{
    s_sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (s_sock < 0) return ESP_FAIL;
    struct sockaddr_in a = {
        .sin_family = AF_INET,
        .sin_port = htons(PN_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(s_sock, (struct sockaddr *)&a, sizeof(a)) != 0) {
        close(s_sock);
        s_sock = -1;
        return ESP_FAIL;
    }
    struct timeval tv = { .tv_sec = 1 };
    setsockopt(s_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    if (tcpip_callback(add_netif_cb, NULL) != ERR_OK) return ESP_FAIL;
    if (xTaskCreate(pn_task, "phonenet", 4096, NULL, 5, NULL) != pdPASS) return ESP_FAIL;
    return ESP_OK;
}

bool phonenet_is_up(void) { return s_up; }
