//
// Created by Eugene Kobyakov on 8/6/24.
//

#ifndef ANDROID_TUNNEL_NETIF_H
#define ANDROID_TUNNEL_NETIF_H

enum netif_cmd {
    netif_None,
    netif_Start,
    netif_Stop,
};

extern netif_driver android_netif_driver();
extern int android_netif_do(netif_cmd, int fd);


#endif //ANDROID_TUNNEL_NETIF_H
