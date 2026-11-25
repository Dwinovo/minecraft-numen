package com.dwinovo.numen.api.client;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.mind.ClientMachine;

/**
 * 这个客户端进程里那一份 {@link ClientMachine}:第一次用时建,之后一直在,不跟连接走。设置页、语音、连接上的大脑读的都是它。
 *
 * <p>线程:客户端主线程。
 */
public final class ClientMachines {

    private static ClientMachine machine;

    private ClientMachines() {}

    /** 这台电脑的钥匙串、各库与偏好。 */
    public static ClientMachine get() {
        if (machine == null) {
            machine = new ClientMachine(Homes.clientDir(), NumenIo.lanes().io("client"));
        }
        return machine;
    }
}
