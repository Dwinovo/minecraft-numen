package com.dwinovo.numen.api.gametest;

import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;

/** 用例排到某一刻的回调表,每刻遍历用的副本(经 {@code GameTestTickOrderMixin})。 */
public final class TickCallbacks {

    private TickCallbacks() {}

    /**
     * {@code map} 此刻的条目按它的先后抄一份,遍历的是这一份;遍历中删一条(原版执行完一个到点的回调就删它)删的是 {@code map} 里同一个回调。
     * 回调在遍历中新排进 {@code map} 的不在这一份里。
     */
    public static ObjectSet<Object2LongMap.Entry<Runnable>> copyOf(Object2LongMap<Runnable> map) {
        ObjectArrayList<Object2LongMap.Entry<Runnable>> copy = new ObjectArrayList<>();
        for (Object2LongMap.Entry<Runnable> entry : map.object2LongEntrySet()) {
            copy.add(new AbstractObject2LongMap.BasicEntry<>(entry.getKey(), entry.getLongValue()));
        }
        return new AbstractObjectSet<>() {
            @Override
            public ObjectIterator<Object2LongMap.Entry<Runnable>> iterator() {
                return new ObjectIterator<>() {
                    private int next;
                    private Object2LongMap.Entry<Runnable> last;

                    @Override
                    public boolean hasNext() {
                        return next < copy.size();
                    }

                    @Override
                    public Object2LongMap.Entry<Runnable> next() {
                        last = copy.get(next++);
                        return last;
                    }

                    @Override
                    public void remove() {
                        map.removeLong(last.getKey());
                    }
                };
            }

            @Override
            public int size() {
                return copy.size();
            }
        };
    }
}
