package com.genymobile.scrcpy;

import android.graphics.Point;
import android.graphics.Rect;

public class Options {
    private int maxSize;
    private int bitRate;
    private boolean tunnelForward;
    private Rect crop;
    private boolean sendFrameMeta; // send PTS so that the client may record properly
    private boolean control;
    private int density = 0;
    private final Point size = new Point(0,0);
    private boolean tabletMode = false;
    private int local_port = 0;
    private boolean useIME = false;
    private boolean disableHWOverlays = false;

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getBitRate() {
        return bitRate;
    }

    public void setBitRate(int bitRate) {
        this.bitRate = bitRate;
    }

    public boolean isTunnelForward() {
        return tunnelForward;
    }

    public void setTunnelForward(boolean tunnelForward) {
        this.tunnelForward = tunnelForward;
    }

    public Rect getCrop() {
        return crop;
    }

    public void setCrop(Rect crop) {
        this.crop = crop;
    }

    public boolean getSendFrameMeta() {
        return sendFrameMeta;
    }

    public void setSendFrameMeta(boolean sendFrameMeta) {
        this.sendFrameMeta = sendFrameMeta;
    }

    public boolean getControl() {
        return control;
    }

    public void setControl(boolean control) {
        this.control = control;
    }

    public int getDensity() { return density; }

    public Point getSize() { return size; }

    public boolean getTabletMode() { return tabletMode; }

    public int getPort() { return local_port; }

    public boolean getUseIME() { return useIME; }

    public boolean getDisableHWOverlays() { return disableHWOverlays; }

    public void setOption(final String option) {
        String[] pair = option.split("=");
        if (pair.length != 2) {
            Ln.w("Expected key=value pair ("+option+")");
            return;
        }
        if ("density".equals(pair[0])) {
            density = Integer.parseInt(pair[1]);
        } else if("size".equals(pair[0])) {
            String[] value=pair[1].split(":");
            if (value.length != 2) value=pair[1].split("x");
            if (value.length != 2) {
                Ln.w("Expected size=width:height ("+option+")");
                return;
            }
            size.x = Integer.parseInt(value[0]);
            size.y = Integer.parseInt(value[1]);
        } else if("tablet".equals(pair[0])) {
            tabletMode = Boolean.parseBoolean(pair[1]);
        } else if("port".equals(pair[0])) {
            local_port = Integer.parseInt(pair[1]);
        } else if("useIME".equals(pair[0])) {
            useIME = Boolean.parseBoolean(pair[1]);
        } else if("disableHWOverlays".equals(pair[0])) {
            disableHWOverlays = Boolean.parseBoolean(pair[1]);
        }
    }
}
