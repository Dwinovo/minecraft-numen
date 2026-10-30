package com.dwinovo.numen.api.agent.scope;

import java.util.ArrayList;
import java.util.List;

/**
 * 一组句柄的归属:一个插件的门把它登记拿到的全部句柄收在这里,关掉作用域就是撤回它登记的一切。作用域自己也是句柄,
 * 所以能嵌进更大的作用域。
 *
 * <p>撤回按登记的反序,一个句柄撤不掉不挡着别的:都撤完之后,第一个失败原样抛出,其余的挂在它的 suppressed 上。
 * 作用域只用一次:关掉之后再收句柄是错(收的那个句柄当场撤掉,免得留下没人管的登记);重登是换一个新的作用域。
 * 线程:任何线程。
 */
public final class Scope implements Handle {

    private final List<Handle> handles = new ArrayList<>();
    private boolean closed;

    /**
     * 收一个句柄;返回它自己,登记处一行写完 {@code scope.add(table.register(...))}。
     *
     * @throws IllegalStateException 作用域已经关了:这个句柄当场撤掉
     */
    public Handle add(Handle handle) {
        synchronized (this) {
            if (!closed) {
                handles.add(handle);
                return handle;
            }
        }
        handle.close();
        throw new IllegalStateException("the scope is closed");
    }

    /** 关过了没有。 */
    public synchronized boolean closed() {
        return closed;
    }

    /** 收着几个句柄。 */
    public synchronized int size() {
        return handles.size();
    }

    @Override
    public void close() {
        List<Handle> closing;
        synchronized (this) {
            closed = true;
            closing = new ArrayList<>(handles);
            handles.clear();
        }
        RuntimeException first = null;
        for (int i = closing.size() - 1; i >= 0; i--) {
            try {
                closing.get(i).close();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
