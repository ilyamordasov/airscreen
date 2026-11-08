#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>

#include "packet_bounds.h"

static unsigned tests;

static void test_nal_bounds(void)
{
    unsigned char valid[] = {0, 0, 0, 2, 0x65, 0x01};
    uint32_t length = 0;
    for (size_t size = 0; size < sizeof(valid); size++) {
        unsigned char *packet = malloc(size ? size : 1);
        assert(packet);
        memcpy(packet, valid, size);
        assert(!airplay_nal_span(packet, size, 0, &length));
        free(packet);
        tests++;
    }
    assert(airplay_nal_span(valid, sizeof(valid), 0, &length) && length == 2);
    assert(!airplay_nal_span(valid, sizeof(valid), SIZE_MAX, &length));
    assert(!airplay_nal_span(valid, sizeof(valid), sizeof(valid), &length));
    assert(!airplay_nal_span(NULL, 6, 0, &length));
    assert(!airplay_nal_span(valid, 6, 0, NULL));
    valid[3] = 0;
    assert(!airplay_nal_span(valid, sizeof(valid), 0, &length));
    memset(valid, 0xff, 4);
    assert(!airplay_nal_span(valid, sizeof(valid), 0, &length));
    tests += 7;
}

static void test_codec_configs(void)
{
    unsigned char avc[] = {1, 100, 0, 31, 0xff, 0xe1, 0, 3, 0x67, 0x42, 0,
                           1, 0, 2, 0x68, 0xce};
    airplay_codec_config_t config;
    for (size_t size = 0; size < sizeof(avc); size++) {
        unsigned char *packet = malloc(size ? size : 1);
        assert(packet);
        memcpy(packet, avc, size);
        assert(!airplay_h264_config(packet, size, &config));
        free(packet);
        tests++;
    }
    assert(airplay_h264_config(avc, sizeof(avc), &config));
    assert(config.sps == avc + 8 && config.sps_size == 3);
    assert(config.pps == avc + 14 && config.pps_size == 2);
    avc[6] = avc[7] = 0xff;
    assert(!airplay_h264_config(avc, sizeof(avc), &config));
    avc[6] = 0;
    avc[7] = 3;
    avc[12] = avc[13] = 0xff;
    assert(!airplay_h264_config(avc, sizeof(avc), &config));
    tests += 5;

    unsigned char hevc[0x75 + 3 * 7] = {0};
    memcpy(hevc + 4, "hvc1", 4);
    for (size_t i = 0; i < 3; i++) {
        size_t offset = 0x75 + i * 7;
        hevc[offset] = 0xa0 + i;
        hevc[offset + 2] = 1;
        hevc[offset + 4] = 2;
        hevc[offset + 5] = 0x40 + (i << 1);
        hevc[offset + 6] = 1;
    }
    for (size_t size = 0; size < sizeof(hevc); size++) {
        unsigned char *packet = malloc(size ? size : 1);
        assert(packet);
        memcpy(packet, hevc, size);
        assert(!airplay_h265_config(packet, size, &config));
        free(packet);
        tests++;
    }
    assert(airplay_h265_config(hevc, sizeof(hevc), &config));
    assert(config.vps == hevc + 0x7a && config.vps_size == 2);
    assert(config.sps == hevc + 0x81 && config.sps_size == 2);
    assert(config.pps == hevc + 0x88 && config.pps_size == 2);
    hevc[0x75 + 3] = hevc[0x75 + 4] = 0xff;
    assert(!airplay_h265_config(hevc, sizeof(hevc), &config));
    tests += 5;
}

static void test_bounded_random_packets(void)
{
    uint32_t seed = 0x83d5b14e;
    for (unsigned iteration = 0; iteration < 10000; iteration++) {
        seed = seed * 1664525u + 1013904223u;
        size_t size = seed % 512;
        unsigned char *packet = malloc(size ? size : 1);
        assert(packet);
        for (size_t i = 0; i < size; i++) {
            seed = seed * 1664525u + 1013904223u;
            packet[i] = seed >> 24;
        }
        uint32_t length = 0;
        if (airplay_nal_span(packet, size, 0, &length)) {
            assert(length > 0 && length <= size - 4);
        }
        airplay_codec_config_t config;
        if (airplay_h264_config(packet, size, &config)) {
            assert(config.sps >= packet && config.sps + config.sps_size <= packet + size);
            assert(config.pps >= packet && config.pps + config.pps_size <= packet + size);
        }
        if (airplay_h265_config(packet, size, &config)) {
            assert(config.vps >= packet && config.vps + config.vps_size <= packet + size);
            assert(config.sps >= packet && config.sps + config.sps_size <= packet + size);
            assert(config.pps >= packet && config.pps + config.pps_size <= packet + size);
        }
        free(packet);
    }
}

int main(void)
{
    test_nal_bounds();
    test_codec_configs();
    test_bounded_random_packets();
    printf("Mirror packet bounds: %u cases + 10000 deterministic random packets passed\n", tests);
    return 0;
}
