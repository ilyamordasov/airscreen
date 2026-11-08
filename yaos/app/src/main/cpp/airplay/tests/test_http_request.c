/* Host-side regression tests for the actual RTSP/HTTP parser. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "http_request.h"

static unsigned tests;

static http_request_t *parse_fragments(const char *data, size_t size, size_t chunk)
{
    http_request_t *request = http_request_init();
    assert(request);
    for (size_t offset = 0; offset < size && !http_request_has_error(request);) {
        size_t count = size - offset < chunk ? size - offset : chunk;
        /* No readable padding: ASan catches callbacks reading beyond a TCP
         * fragment, including the former on_url protocol look-ahead. */
        char *fragment = malloc(count);
        assert(fragment);
        memcpy(fragment, data + offset, count);
        http_request_add_data(request, fragment, (int) count);
        free(fragment);
        offset += count;
    }
    return request;
}

static void expect_error(const char *data, size_t size)
{
    http_request_t *request = parse_fragments(data, size, 127);
    assert(http_request_has_error(request));
    http_request_destroy(request);
    tests++;
}

static void test_fragmented_requests(void)
{
    static const char message[] = "GET /info RTSP/1.0\r\ncseq: 7\r\nX-Empty:\r\nX-Next: abc\r\n\r\n";
    for (size_t chunk = 1; chunk <= sizeof(message); chunk++) {
        http_request_t *request = parse_fragments(message, sizeof(message) - 1, chunk);
        if (http_request_has_error(request)) {
            fprintf(stderr, "fragment %zu: %s\n", chunk, http_request_get_error_description(request));
        }
        assert(!http_request_has_error(request));
        assert(http_request_is_complete(request));
        assert(!strcmp(http_request_get_protocol(request), "RTSP/1.0"));
        assert(!strcmp(http_request_get_url(request), "/info"));
        assert(!strcmp(http_request_get_method(request), "GET"));
        assert(!strcmp(http_request_get_header(request, "CSeq"), "7"));
        assert(!strcmp(http_request_get_header(request, "X-Empty"), ""));
        assert(!strcmp(http_request_get_header(request, "x-next"), "abc"));
        int fields = 0;
        size_t name = 0, value = 0;
        assert(http_request_header_get_size(request, &fields, &name, &value) == 21);
        assert(fields == 3 && name == 7 && value == 3);
        http_request_destroy(request);
        tests++;
    }
    static const char http[] = "POST /feedback HTTP/1.1\r\nContent-Length: 5\r\n\r\na\0b\nc";
    http_request_t *request = parse_fragments(http, sizeof(http) - 1, 1);
    assert(!http_request_has_error(request));
    assert(http_request_is_complete(request));
    assert(!strcmp(http_request_get_protocol(request), "HTTP/1.1"));
    int size = 0;
    const char *body = http_request_get_data(request, &size);
    assert(size == 5 && !memcmp(body, "a\0b\nc", 5));
    http_request_destroy(request);
    tests++;
}

static void test_limits(void)
{
    char length_header[128];
    snprintf(length_header, sizeof(length_header),
             "POST /feedback RTSP/1.0\r\nContent-Length: %u\r\n\r\n",
             HTTP_REQUEST_MAX_BODY + 1);
    expect_error(length_header, strlen(length_header));
    static const char integer_overflow[] =
        "POST /feedback RTSP/1.0\r\nContent-Length: 18446744073709551616\r\n\r\n";
    expect_error(integer_overflow, sizeof(integer_overflow) - 1);

    char *message = calloc(HTTP_REQUEST_MAX_URL + 128, 1);
    assert(message);
    strcpy(message, "GET /");
    memset(message + 5, 'x', HTTP_REQUEST_MAX_URL);
    strcpy(message + 5 + HTTP_REQUEST_MAX_URL, " RTSP/1.0\r\n\r\n");
    expect_error(message, strlen(message));
    free(message);

    message = calloc(HTTP_REQUEST_MAX_HEADER_VALUE + 256, 1);
    assert(message);
    strcpy(message, "GET /info RTSP/1.0\r\nX-Test: ");
    size_t prefix = strlen(message);
    memset(message + prefix, 'a', HTTP_REQUEST_MAX_HEADER_VALUE + 1);
    strcpy(message + prefix + HTTP_REQUEST_MAX_HEADER_VALUE + 1, "\r\n\r\n");
    expect_error(message, strlen(message));
    free(message);

    message = calloc(4096, 1);
    assert(message);
    strcpy(message, "GET /info RTSP/1.0\r\n");
    for (unsigned i = 0; i < HTTP_REQUEST_MAX_HEADER_FIELDS + 1; i++) {
        char header[64];
        snprintf(header, sizeof(header), "X-%u: value\r\n", i);
        strcat(message, header);
    }
    strcat(message, "\r\n");
    expect_error(message, strlen(message));
    free(message);

    /* Enforce the same body cap for chunked data, not just Content-Length. */
    static const char chunk_header[] =
        "POST /feedback HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n800001\r\n";
    size_t total = sizeof(chunk_header) - 1 + HTTP_REQUEST_MAX_BODY + 1 + 7;
    message = malloc(total);
    assert(message);
    memcpy(message, chunk_header, sizeof(chunk_header) - 1);
    memset(message + sizeof(chunk_header) - 1, 'a', HTTP_REQUEST_MAX_BODY + 1);
    memcpy(message + total - 7, "\r\n0\r\n\r\n", 7);
    http_request_t *request = parse_fragments(message, total, 32768);
    assert(http_request_has_error(request));
    http_request_destroy(request);
    free(message);
    tests++;

    static const char pipelined[] = "GET /info RTSP/1.0\r\n\r\nGET /info RTSP/1.0\r\n\r\n";
    expect_error(pipelined, sizeof(pipelined) - 1);
}

int main(int argc, char **argv)
{
    test_fragmented_requests();
    if (argc <= 1 || strcmp(argv[1], "--fragmentation-only")) {
        test_limits();
    }
    printf("HTTP/RTSP parser: %u regression cases passed\n", tests);
    return 0;
}
