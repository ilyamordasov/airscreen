/**
 *  Copyright (C) 2011-2012  Juho Vähä-Herttua
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *==================================================================
 * modified by fduncanh 2021
 */

#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <assert.h>
#include <stdio.h>

#include "http_request.h"
#include "llhttp/llhttp.h"

struct http_request_s {
    llhttp_t parser;
    llhttp_settings_t parser_settings;

    bool is_reverse;  // if true, this is a reverse-response from client
    const char *method;
    char *url;
    char protocol[9];

    char **headers;
    int headers_size;
    int headers_index;

    char *data;
    int datalen;

    int complete;
};

static int
parse_error(llhttp_t *parser, const char *reason)
{
    llhttp_set_error_reason(parser, reason);
    return HPE_USER;
}

static int
append_string(llhttp_t *parser, char **value, const char *at, size_t length, size_t limit)
{
    size_t current = *value ? strlen(*value) : 0;
    if (current > limit || length > limit - current) {
        return parse_error(parser, "RTSP/HTTP field exceeds limit");
    }
    char *next = realloc(*value, current + length + 1);
    if (!next) {
        return parse_error(parser, "Out of memory parsing RTSP/HTTP field");
    }
    memcpy(next + current, at, length);
    next[current + length] = '\0';
    *value = next;
    return 0;
}

static int
on_message_begin(llhttp_t *parser)
{
    http_request_t *request = parser->data;
    /* This server processes one request at a time. Never merge a second
     * pipelined message into the first request's headers/body. */
    return request->complete ? parse_error(parser, "Pipelined request is unsupported") : 0;
}

static int
on_protocol(llhttp_t *parser, const char *at, size_t length)
{
    http_request_t *request = parser->data;
    size_t used = strlen(request->protocol);
    if (used > 4 || length > 4 - used) {
        return parse_error(parser, "Unsupported RTSP/HTTP protocol");
    }
    memcpy(request->protocol + used, at, length);
    request->protocol[used + length] = '\0';
    return 0;
}

static int
on_protocol_complete(llhttp_t *parser)
{
    http_request_t *request = parser->data;
    if (strcmp(request->protocol, "RTSP") && strcmp(request->protocol, "HTTP")) {
        return parse_error(parser, "Unsupported RTSP/HTTP protocol");
    }
    memcpy(request->protocol + 4, "/", 2);
    return 0;
}

static int
on_version(llhttp_t *parser, const char *at, size_t length)
{
    http_request_t *request = parser->data;
    size_t used = strlen(request->protocol);
    if (used >= sizeof(request->protocol) ||
        length >= sizeof(request->protocol) - used) {
        return parse_error(parser, "Unsupported RTSP/HTTP version");
    }
    memcpy(request->protocol + used, at, length);
    request->protocol[used + length] = '\0';
    return 0;
}

static int
on_url(llhttp_t *parser, const char *at, size_t length)
{
    http_request_t *request = parser->data;
    /* llhttp may split a URL at any TCP byte boundary. Its callback only owns
     * [at, at+length), so the protocol must be read in its own callbacks. */
    return append_string(parser, &request->url, at, length, HTTP_REQUEST_MAX_URL);
}

static int
on_header_field(llhttp_t *parser, const char *at, size_t length)
{
    http_request_t *request = parser->data;

    /* Allocate space for new field-value pair */
    if (request->headers_index == request->headers_size) {
        if (request->headers_size / 2 >= HTTP_REQUEST_MAX_HEADER_FIELDS) {
            return parse_error(parser, "Too many RTSP/HTTP headers");
        }
        char **next = realloc(request->headers, (request->headers_size + 2) * sizeof(char *));
        if (!next) {
            return parse_error(parser, "Out of memory parsing RTSP/HTTP headers");
        }
        request->headers = next;
        next[request->headers_size] = NULL;
        next[request->headers_size + 1] = calloc(1, 1);
        request->headers_size += 2;
        if (!next[request->headers_size - 1]) {
            return parse_error(parser, "Out of memory parsing RTSP/HTTP headers");
        }
    }
    return append_string(parser, &request->headers[request->headers_index], at, length,
                         HTTP_REQUEST_MAX_HEADER_NAME);
}

static int
on_header_field_complete(llhttp_t *parser)
{
    http_request_t *request = parser->data;
    request->headers_index++;
    return 0;
}

static int
on_header_value(llhttp_t *parser, const char *at, size_t length)
{
    http_request_t *request = parser->data;

    return append_string(parser, &request->headers[request->headers_index], at, length,
                         HTTP_REQUEST_MAX_HEADER_VALUE);
}

static int
on_header_value_complete(llhttp_t *parser)
{
    http_request_t *request = parser->data;
    request->headers_index++;
    return 0;
}

static int
on_headers_complete(llhttp_t *parser)
{
    if (parser->content_length > HTTP_REQUEST_MAX_BODY) {
        return parse_error(parser, "RTSP/HTTP body exceeds limit");
    }
    return 0;
}

static int
on_body(llhttp_t *parser, const char *at, size_t length)
{
    http_request_t *request = parser->data;

    if (length > HTTP_REQUEST_MAX_BODY - (size_t) request->datalen) {
        return parse_error(parser, "RTSP/HTTP body exceeds limit");
    }
    char *next = realloc(request->data, (size_t) request->datalen + length + 1);
    if (!next) {
        return parse_error(parser, "Out of memory parsing RTSP/HTTP body");
    }
    request->data = next;
    memcpy(request->data + request->datalen, at, length);
    request->datalen += (int) length;
    request->data[request->datalen] = '\0';
    return 0;
}

static int
on_message_complete(llhttp_t *parser)
{
    http_request_t *request = parser->data;

    request->method = llhttp_method_name(request->parser.method);
    request->complete = 1;
    return 0;
}

http_request_t *
http_request_init(void)
{
    http_request_t *request = calloc(1, sizeof(http_request_t));
    if (!request) {
        return NULL;
    }

    llhttp_settings_init(&request->parser_settings);
    request->parser_settings.on_message_begin = &on_message_begin;
    request->parser_settings.on_protocol = &on_protocol;
    request->parser_settings.on_protocol_complete = &on_protocol_complete;
    request->parser_settings.on_version = &on_version;
    request->parser_settings.on_url = &on_url;
    request->parser_settings.on_header_field = &on_header_field;
    request->parser_settings.on_header_field_complete = &on_header_field_complete;
    request->parser_settings.on_header_value = &on_header_value;
    request->parser_settings.on_header_value_complete = &on_header_value_complete;
    request->parser_settings.on_headers_complete = &on_headers_complete;
    request->parser_settings.on_body = &on_body;
    request->parser_settings.on_message_complete = &on_message_complete;

    llhttp_init(&request->parser, HTTP_REQUEST, &request->parser_settings);
    request->parser.data = request;
    request->is_reverse = false;
    return request;
}

void
http_request_destroy(http_request_t *request)
{
    if (request) {
        free(request->url);
        for (int i = 0; i < request->headers_size; i++) {
            free(request->headers[i]);
        }
        free(request->headers);
        free(request->data);
        free(request);
    }
}

int
http_request_add_data(http_request_t *request, const char *data, int datalen)
{
    assert(request);

    if (datalen < 0 || (!data && datalen)) {
        llhttp_pause(&request->parser);
        llhttp_set_error_reason(&request->parser, "Invalid RTSP/HTTP input buffer");
        return -1;
    }
    int ret = llhttp_execute(&request->parser, data, datalen);

    /* support for "Upgrade" to reverse http ("PTTH/1.0") protocol */
    llhttp_resume_after_upgrade(&request->parser);

    return ret;
}

int
http_request_is_complete(http_request_t *request)
{
    assert(request);
    return request->complete;
}

int
http_request_has_error(http_request_t *request)
{
    assert(request);
    if (request->is_reverse) {
        return 0;
    }
    return (llhttp_get_errno(&request->parser) != HPE_OK);
}

const char *
http_request_get_error_name(http_request_t *request)
{
    assert(request);
    if (request->is_reverse) {
        return NULL;
    }
    return llhttp_errno_name(llhttp_get_errno(&request->parser));
}

const char *
http_request_get_error_description(http_request_t *request)
{
    assert(request);
    if (request->is_reverse) {
        return NULL;
    }
    return llhttp_get_error_reason(&request->parser);
}

const char *
http_request_get_method(http_request_t *request)
{
    assert(request);
    if (request->is_reverse) {
        return NULL;
    }
    return request->method;
}

const char *
http_request_get_url(http_request_t *request)
{
    assert(request);
    if (request->is_reverse) {
        return NULL;
    }
    return request->url;
}

const char *
http_request_get_protocol(http_request_t *request)
{
    assert(request);
    if (request->is_reverse) {
        return NULL;
    }
    return request->protocol;
}

const char *
http_request_get_header(http_request_t *request, const char *name)
{
    assert(request);
    if (request->is_reverse) {
        return NULL;
    }

    for (int i = 0; i < request->headers_size; i += 2) {
        if (request->headers[i] && !strcasecmp(request->headers[i], name)) {
            return request->headers[i+1];
        }
    }
    return NULL;
}

size_t
http_request_header_get_size(http_request_t *request, int *num_fields, size_t *max_field_len, size_t *max_value_len) {
    size_t total = 0;
    if (max_field_len) {
        *max_field_len = 0;
    }
    if (max_value_len) {
        *max_value_len = 0;
    }
    if (num_fields) {
        *num_fields = request->headers_size / 2;
    }
    for (int i = 0; i < request->headers_size; i++) {
        size_t len = request->headers[i] ? strlen(request->headers[i]) : 0;
        total += len;
        if (i % 2 == 0 && max_field_len && len > *max_field_len) {
            *max_field_len = len;
        } else if (i % 2 == 1 && max_value_len && len > *max_value_len) {
            *max_value_len = len;
        }
    }
    return total;
}

const char *
http_request_get_data(http_request_t *request, int *datalen)
{
    assert(request);
    if (datalen) {
        *datalen = request->datalen;
    }
    return request->data;
}

int 
http_request_get_header_string(http_request_t *request, char **header_str)
{
    if(!request || request->headers_size == 0) {
        *header_str = NULL;
        return 0;
    }
    if (request->is_reverse) {
        *header_str = NULL;
        return 0;
    }    
    int len = 0;
    for (int i = 0; i < request->headers_size; i++) {
        len += strlen(request->headers[i]);
        if (i % 2 == 0) {
            len += 2;
        } else {
            len++;
        }
    }
    char *str = (char *) calloc(len+1, sizeof(char));
    assert(str);
    *header_str = str;
    char *p = str;
    int n = len + 1;
    for (int i = 0; i < request->headers_size; i++) {
        int hlen = strlen(request->headers[i]); 
        snprintf(p, n, "%s", request->headers[i]);
        n -= hlen;
        p += hlen;
        if (i % 2 == 0) {
            snprintf(p, n, ": ");
            n -= 2;
            p += 2;
        } else {
            snprintf(p, n, "\n");
            n--;
            p++;
        }
    }
    assert(p == &(str[len]));
    return len;
}

bool http_request_is_reverse(http_request_t *request) {
    return request->is_reverse;
}

void http_request_set_reverse(http_request_t *request) {
    request->is_reverse = true;
}
