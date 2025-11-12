/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

#include <jni.h>

#include <ziti/ziti_tunnel.h>
#include <ziti/ziti_log.h>
#include <ziti/enums.h>
#include "metrics.h"

#include "netif.h"

struct netif_handle_s {
    packet_cb on_packet;
    void* on_packet_ctx;

    uv_loop_t *loop;
    uv_pipe_t *if_pipe;

    rate_t up;
    rate_t down;
};

static netif_handle_s NETIF;
static int netif_setup(netif_handle, uv_loop_t *, packet_cb , void *);
static ssize_t netif_write(netif_handle, const void *b, size_t len);
static int add_route(netif_handle, const char *string1);
static int del_route(netif_handle, const char *string2);
static int commit(netif_handle, uv_loop_t *pS);




static netif_driver_t android_netif = {
        .handle = &NETIF,
        .read = nullptr,
        .write = netif_write,
        .close = nullptr,
        .setup = netif_setup,
        .add_route = add_route,
        .delete_route = del_route,
        .commit_routes = commit,
};

netif_driver android_netif_driver() {
    return &android_netif;
}

static int netif_setup(netif_handle, uv_loop_t *loop, packet_cb on_packet, void *ctx) {
    ZITI_LOG(INFO, "setting up android netif");
    NETIF.loop = loop;
    NETIF.on_packet = on_packet;
    NETIF.on_packet_ctx = ctx;
    metrics_rate_init(&NETIF.up, INSTANT);
    metrics_rate_init(&NETIF.down, INSTANT);
    return 0;
}

extern int tunnel_add_route(netif_handle, const char *route);
extern int tunnel_del_route(netif_handle, const char *route);
extern int tunnel_commit(netif_handle, uv_loop_t *);

static int add_route(netif_handle h, const char *route) {
    return tunnel_add_route(h, route);
}

static int del_route(netif_handle h, const char *route) {
    return tunnel_del_route(h, route);
}

static int commit(netif_handle h, uv_loop_t *l) {
    return tunnel_commit(h, l);
}

static ssize_t netif_write(netif_handle, const void *b, size_t len) {
    if (NETIF.if_pipe) {
        auto buf = uv_buf_init((char*)b, len);
        metrics_rate_update(&NETIF.down, (long)len);
        return uv_try_write((uv_stream_t *)NETIF.if_pipe, &buf, 1);
    }
    return -1;
}

static char netif_buf[64 * 1024];
static void netif_alloc(uv_handle_t *, size_t i, uv_buf_t *b) {
    b->base = netif_buf;
    b->len = sizeof(netif_buf);
}

static void netif_read(uv_stream_t *, ssize_t len, const uv_buf_t *b) {
    if (len > 0) {
        metrics_rate_update(&NETIF.up, (long)len);
        NETIF.on_packet(b->base, len, NETIF.on_packet_ctx);
    } else if (len < 0) {
        ZITI_LOG(WARN, "read error: %s", uv_strerror((int)len));
    }
}

jdouble JNICALL get_up_rate(JNIEnv *, jobject) {
    double up = 0.0;
    metrics_rate_get(&NETIF.up, &up);
    return up;
}
jdouble JNICALL get_down_rate(JNIEnv *, jobject) {
    double down = 0.0;
    metrics_rate_get(&NETIF.down, &down);
    return down;
}

static void close_net_if() {
    if (NETIF.if_pipe != nullptr) {
        uv_os_fd_t old_fd = -1;
        uv_fileno((uv_handle_t*)NETIF.if_pipe, &old_fd);
        ZITI_LOG(INFO, "stopping android netif fd[%d]", old_fd);
        uv_close((uv_handle_t*)NETIF.if_pipe, (uv_close_cb)free);
        NETIF.if_pipe = nullptr;
    }
}

void android_netif_start(uv_loop_t *loop, void *arg) {
    int fd = (int)(intptr_t)arg;
    if (NETIF.if_pipe != nullptr) {
        uv_os_fd_t old_fd = -1;
        uv_fileno((uv_handle_t *) NETIF.if_pipe, &old_fd);
        if (old_fd == fd) {
            ZITI_LOG(INFO, "old_fd[%d] == fd[%d]", old_fd, fd);
            return;
        }
    }
    ZITI_LOG(INFO, "starting android netif fd[%d]", fd);
    auto p = (uv_pipe_t*)calloc(1, sizeof(uv_pipe_t));
    int rc = uv_pipe_init(NETIF.loop, p, 0);
    if (rc != 0) {
        ZITI_LOG(WARN, "failed to init fd[%d]: %s", fd, uv_strerror(rc));
        return;
    }
    rc = uv_pipe_open(p, fd);
    if (rc != 0) {
        ZITI_LOG(WARN, "failed to open pipe fd[%d]: %s", fd, uv_strerror(rc));
        return;
    }
    close_net_if();

    NETIF.if_pipe = p;
    rc = uv_read_start((uv_stream_t *)p, netif_alloc, netif_read);
    if (rc != 0) {
        ZITI_LOG(WARN, "failed to start reading pipe fd[%d]: %s", fd, uv_strerror(rc));
        return;
    }
}

void android_netif_stop(uv_loop_t *l, void *arg) {
    close_net_if();
}

