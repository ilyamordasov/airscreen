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
 */

#ifndef HTTP_REQUEST_H
#define HTTP_REQUEST_H

#include <stdbool.h>
#include <stddef.h>

/* Control traffic and cover art are bounded independently of video frames. */
#define HTTP_REQUEST_MAX_URL 8192
#define HTTP_REQUEST_MAX_HEADER_FIELDS 20
#define HTTP_REQUEST_MAX_HEADER_NAME 64
#define HTTP_REQUEST_MAX_HEADER_VALUE 1024
#define HTTP_REQUEST_MAX_BODY (8 * 1024 * 1024)

typedef struct http_request_s http_request_t;

http_request_t *http_request_init(void);

int http_request_add_data(http_request_t *request, const char *data, int datalen);
int http_request_is_complete(http_request_t *request);
int http_request_has_error(http_request_t *request);

const char *http_request_get_error_name(http_request_t *request);
const char *http_request_get_error_description(http_request_t *request);
const char *http_request_get_method(http_request_t *request);
const char *http_request_get_url(http_request_t *request);
const char *http_request_get_protocol(http_request_t *request);
const char *http_request_get_header(http_request_t *request, const char *name);
const char *http_request_get_data(http_request_t *request, int *datalen);
size_t http_request_header_get_size(http_request_t *request, int *fields, size_t *max_field_len, size_t *max_value_len);
int http_request_get_header_string(http_request_t *request, char **header_str);
bool http_request_is_reverse(http_request_t *request);
void http_request_set_reverse(http_request_t *request);

void http_request_destroy(http_request_t *request);

#endif
