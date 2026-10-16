package com.dwinovo.numen.api.store;

/** 读写磁盘失败,或文件读不懂。消息里带着是哪个文件、做什么时出的错。 */
public final class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
