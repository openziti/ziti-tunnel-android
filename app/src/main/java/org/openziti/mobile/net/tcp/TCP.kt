/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net.tcp

interface TCP {

    // TCP connection state and transition are implemented according to
    // https://gist.github.com/GordonMcKinney/52518273bd1904507d5d
    enum class State {
        LISTEN,
        SYN_RCVD,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK,
        FIN_WAIT_1,
        FIN_WAIT_2,
        CLOSING,
        TIME_WAIT,
        Closed,
    }
}