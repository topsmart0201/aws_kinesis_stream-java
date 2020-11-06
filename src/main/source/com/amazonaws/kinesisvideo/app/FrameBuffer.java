package com.amazonaws.kinesisvideo.app;

import java.util.Arrays;

public class FrameBuffer {
    public byte[] bytes;
    private int width;
    private int height;
    private long pts;
    public FrameBuffer(byte[] b, int w, int h) {
        this.bytes = Arrays.copyOf(b, b.length);
        this.width = w;
        this.height = h;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getPts() {
        return pts;
    }

    public void setPts(long pts) {
        this.pts = pts;
    }
}
