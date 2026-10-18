#pragma once
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Start the relay. Call after the Wi-Fi AP is running. */
esp_err_t phonenet_start(void);

/** True while the Android relay is connected. */
bool phonenet_is_up(void);

#ifdef __cplusplus
}
#endif
