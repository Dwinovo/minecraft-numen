package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.network.payload.ExportChunkPayload;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * 一份字节按段下行的两头:发的一方把它切成每段至多 {@link ExportChunkPayload#SEGMENT_BYTES} 的几段,每刻交出一段({@link Cut});
 * 收的一方按序接起来({@link Assembler})。导出的记忆({@link Exports})和旧皮肤库里的原图({@link SkinHandover})都是这样下行的,
 * 一份大小不设上限,而每段都装得进一个包。
 */
public final class Segments {

    private Segments() {}

    /** 一份字节切出来的段,按序取。 */
    public static final class Cut {
        private final byte[] bytes;
        private final int total;
        private int next;

        public Cut(byte[] bytes) {
            this.bytes = bytes;
            this.total = Math.max(1, (bytes.length + ExportChunkPayload.SEGMENT_BYTES - 1) / ExportChunkPayload.SEGMENT_BYTES);
        }

        /** 一共几段(空的也是一段)。 */
        public int total() {
            return total;
        }

        /** 下一段是第几段,从 0 起。 */
        public int next() {
            return next;
        }

        /** 取下一段。 */
        public byte[] take() {
            int from = next++ * ExportChunkPayload.SEGMENT_BYTES;
            return Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + ExportChunkPayload.SEGMENT_BYTES));
        }

        public boolean done() {
            return next >= total;
        }
    }

    /** 客户端的拼回:按序收齐,乱序或总段数变了就作废。 */
    public static final class Assembler {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int expected;
        private int total = -1;

        /**
         * @return 这一段接上了;顺序不对(丢了、重了、总段数变了)是 {@code false},这一份该作废
         */
        public boolean accept(int index, int total, byte[] segment) {
            if (index != expected || this.total >= 0 && total != this.total) {
                return false;
            }
            this.total = total;
            bytes.writeBytes(segment);
            expected++;
            return true;
        }

        public boolean complete() {
            return total >= 0 && expected == total;
        }

        public byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
