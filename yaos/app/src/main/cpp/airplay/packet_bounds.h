/* Length checks shared by the mirror receiver and its host regression tests.
 * Keep untrusted packet sizes unsigned until they have been validated. */
#ifndef AIRPLAY_PACKET_BOUNDS_H
#define AIRPLAY_PACKET_BOUNDS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define AIRPLAY_MAX_MIRROR_PAYLOAD (16 * 1024 * 1024)

typedef struct {
    const unsigned char *vps;
    const unsigned char *sps;
    const unsigned char *pps;
    size_t vps_size;
    size_t sps_size;
    size_t pps_size;
} airplay_codec_config_t;

static inline bool
airplay_nal_span(const unsigned char *data, size_t size, size_t offset, uint32_t *length)
{
    if (!data || !length || offset > size || size - offset <= 4) {
        return false;
    }
    uint32_t value = ((uint32_t) data[offset] << 24) |
                     ((uint32_t) data[offset + 1] << 16) |
                     ((uint32_t) data[offset + 2] << 8) |
                     (uint32_t) data[offset + 3];
    if (!value || value > size - offset - 4) {
        return false;
    }
    *length = value;
    return true;
}

static inline bool
airplay_h264_config(const unsigned char *data, size_t size, airplay_codec_config_t *out)
{
    if (!data || !out || size < 11) {
        return false;
    }
    size_t sps = ((size_t) data[6] << 8) | data[7];
    if (!sps || sps > size - 11) {
        return false;
    }
    size_t pps = ((size_t) data[sps + 9] << 8) | data[sps + 10];
    if (!pps || pps > size - sps - 11) {
        return false;
    }
    memset(out, 0, sizeof(*out));
    out->sps = data + 8;
    out->sps_size = sps;
    out->pps = data + sps + 11;
    out->pps_size = pps;
    return true;
}

static inline bool
airplay_h265_config(const unsigned char *data, size_t size, airplay_codec_config_t *out)
{
    if (!data || !out || size < 0x75) {
        return false;
    }
    const unsigned char *units[3];
    size_t lengths[3];
    size_t offset = 0x75;
    for (size_t i = 0; i < 3; i++) {
        if (offset > size || size - offset < 5 ||
            data[offset] != 0xa0 + i || data[offset + 1] != 0 || data[offset + 2] != 1) {
            return false;
        }
        size_t length = ((size_t) data[offset + 3] << 8) | data[offset + 4];
        if (!length || length > size - offset - 5) {
            return false;
        }
        units[i] = data + offset + 5;
        lengths[i] = length;
        offset += 5 + length;
    }
    out->vps = units[0];
    out->sps = units[1];
    out->pps = units[2];
    out->vps_size = lengths[0];
    out->sps_size = lengths[1];
    out->pps_size = lengths[2];
    return true;
}

#endif
