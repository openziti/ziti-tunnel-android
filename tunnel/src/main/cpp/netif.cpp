//
// Created by Eugene Kobyakov on 7/27/24.
//

#include <ziti/ziti_tunnel.h>
#include <ziti/ziti_log.h>

struct netif_handle_s {
    packet_cb on_packet;
    void* on_packet_ctx;
};
static netif_handle_s NETIF;
static int netif_setup(netif_handle, uv_loop_t *, packet_cb , void *);
static int add_route(netif_handle, const char *string1);
static int del_route(netif_handle, const char *string2);
static int commit(netif_handle, uv_loop_t *pS);


static netif_driver_t android_netif = {
        .handle = &NETIF,
        .read = nullptr,
        .write = nullptr,
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
    NETIF.on_packet = on_packet;
    NETIF.on_packet_ctx = ctx;
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
