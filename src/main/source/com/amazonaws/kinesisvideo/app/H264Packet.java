package com.amazonaws.kinesisvideo.app;

import java.util.Arrays;

public class H264Packet {
    public byte[] bytes;
    private long pts;
    private long dts;
    private long duration;
    private int flags;

    public H264Packet(byte[] bytes, long pts, long dts, long duration, int flags) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.pts = pts;
        this.dts = dts;
        this.duration = duration;
        this.flags = flags;
    }

    public long getPts() {
        return pts;
    }

    public long getDts() {
        return dts;
    }

    public long getDuration() {
        return duration;
    }

    public int getFlags() {
        return flags;
    }
}
