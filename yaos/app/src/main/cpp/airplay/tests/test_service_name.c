#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "service_name.h"

int main(void)
{
    char out[AIRPLAY_MAX_SERVICE_NAME_BYTES + 1];
    const uint16_t russian[] = {0x0422, 0x0412, ' ', 0xd83d, 0xdcfa};
    assert(airplay_service_name_from_utf16(russian, 5, out));
    assert(!strcmp(out, "\xd0\xa2\xd0\x92 \xf0\x9f\x93\xba"));
    assert(strlen(out) == 9);

    uint16_t name[51];
    for (size_t i = 0; i < 51; i++) name[i] = 'a';
    assert(airplay_service_name_from_utf16(name, 50, out));
    assert(strlen(out) == 50);
    assert(!airplay_service_name_from_utf16(name, 51, out));
    for (size_t i = 0; i < 26; i++) name[i] = 0x0410;
    assert(airplay_service_name_from_utf16(name, 25, out));
    assert(strlen(out) == 50);
    assert(!airplay_service_name_from_utf16(name, 26, out));
    for (size_t i = 0; i < 26; i++) name[i] = i % 2 ? 0xdcfa : 0xd83d;
    assert(airplay_service_name_from_utf16(name, 24, out));
    assert(strlen(out) == 48);
    assert(!airplay_service_name_from_utf16(name, 26, out));
    name[24] = name[25] = 'a';
    assert(airplay_service_name_from_utf16(name, 26, out));
    assert(strlen(out) == 50);

    const uint16_t invalid[] = {0, 0x1f, 0x7f, 0x9f, '.', '\\', 0xd800, 0xdc00};
    for (size_t i = 0; i < sizeof(invalid) / sizeof(*invalid); i++) {
        assert(!airplay_service_name_from_utf16(invalid + i, 1, out));
    }
    const uint16_t bad_pair[] = {0xd800, 'a'};
    assert(!airplay_service_name_from_utf16(bad_pair, 2, out));
    assert(!airplay_service_name_from_utf16(name, 0, out));
    assert(!airplay_service_name_from_utf16(NULL, 1, out));
    assert(!airplay_service_name_from_utf16(name, 1, NULL));
    puts("DNS-SD service name: 26 Unicode and boundary assertions passed");
    return 0;
}
