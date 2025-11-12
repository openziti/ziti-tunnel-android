/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

#ifndef ANDROID_TUNNEL_NETIF_H
#define ANDROID_TUNNEL_NETIF_H

typedef struct uv_loop_s uv_loop_t;
extern netif_driver android_netif_driver();
extern void android_netif_start(uv_loop_t *, void *arg);
extern void android_netif_stop(uv_loop_t *,void*);


#endif //ANDROID_TUNNEL_NETIF_H
