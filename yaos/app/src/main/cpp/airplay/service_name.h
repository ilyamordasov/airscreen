/* Standard UTF-8 for DNS-SD labels. JNI's modified UTF-8 encodes supplementary
 * Unicode characters as six bytes and must not be published on the network. */
#ifndef AIRPLAY_SERVICE_NAME_H
#define AIRPLAY_SERVICE_NAME_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* A RAOP instance adds twelve hexadecimal MAC digits and '@' to the name. */
#define AIRPLAY_MAX_SERVICE_NAME_BYTES 50

static inline bool
airplay_service_name_from_utf16(const uint16_t *src, size_t units,
                               char out[AIRPLAY_MAX_SERVICE_NAME_BYTES + 1])
{
    if (!src || !out || !units || units > AIRPLAY_MAX_SERVICE_NAME_BYTES) return false;
    size_t written = 0;
    for (size_t i = 0; i < units; ++i) {
        uint32_t cp = src[i];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (++i >= units || src[i] < 0xdc00 || src[i] > 0xdfff) return false;
            cp = 0x10000 + ((cp - 0xd800) << 10) + (src[i] - 0xdc00);
        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
            return false;
        }
        /* The small mDNS implementation accepts unescaped presentation names. */
        if (cp < 0x20 || (cp >= 0x7f && cp <= 0x9f) || cp == '.' || cp == '\\') return false;
        size_t bytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        if (bytes > AIRPLAY_MAX_SERVICE_NAME_BYTES - written) return false;
        if (bytes == 1) {
            out[written++] = (char) cp;
        } else if (bytes == 2) {
            out[written++] = (char) (0xc0 | (cp >> 6));
            out[written++] = (char) (0x80 | (cp & 0x3f));
        } else if (bytes == 3) {
            out[written++] = (char) (0xe0 | (cp >> 12));
            out[written++] = (char) (0x80 | ((cp >> 6) & 0x3f));
            out[written++] = (char) (0x80 | (cp & 0x3f));
        } else {
            out[written++] = (char) (0xf0 | (cp >> 18));
            out[written++] = (char) (0x80 | ((cp >> 12) & 0x3f));
            out[written++] = (char) (0x80 | ((cp >> 6) & 0x3f));
            out[written++] = (char) (0x80 | (cp & 0x3f));
        }
    }
    out[written] = '\0';
    return true;
}

#endif
