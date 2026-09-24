package com.femaleshift;

import android.media.AudioFormat;
import android.media.AudioRecord;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sonic.Sonic;

public class MainHook implements IXposedHookLoadPackage {

    private static final float PITCH = 1.35f;
    private static final float SPEED = 1.0f;

    private static final Map<Object, Sonic> SONICS = new WeakHashMap<Object, Sonic>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null) return;

        hookShort();
        hookByte();

        XposedBridge.log("FV: ready in " + lpparam.packageName);
    }

    private static void hookShort() {
        try {
            XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
                    short[].class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int got = (Integer) param.getResult();
                                if (got <= 0) return;
                                short[] buf = (short[]) param.args[0];
                                int off = (Integer) param.args[1];
                                processShorts((AudioRecord) param.thisObject, buf, off, got);
                            } catch (Throwable t) {
                                XposedBridge.log("FV short error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("FV: hook short failed: " + t);
        }
    }

    private static void hookByte() {
        try {
            XposedHelpers.findAndHookMethod(AudioRecord.class, "read",
                    byte[].class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int got = (Integer) param.getResult();
                                if (got < 2) return;
                                byte[] buf = (byte[]) param.args[0];
                                int off = (Integer) param.args[1];
                                processBytes((AudioRecord) param.thisObject, buf, off, got);
                            } catch (Throwable t) {
                                XposedBridge.log("FV byte error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("FV: hook byte failed: " + t);
        }
    }

    private static Sonic getSonic(AudioRecord rec) {
        synchronized (SONICS) {
            Sonic s = SONICS.get(rec);
            if (s == null) {
                int sr = rec.getSampleRate();
                int ch = rec.getChannelCount();
                if (sr <= 0 || ch <= 0) return null;
                s = new Sonic(sr, ch);
                s.setPitch(PITCH);
                s.setSpeed(SPEED);
                SONICS.put(rec, s);
                XposedBridge.log("FV: Sonic start " + sr + "Hz ch=" + ch);
            }
            return s;
        }
    }

    private static boolean ready(AudioRecord rec) {
        return rec.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT;
    }

    private static void processShorts(AudioRecord rec, short[] buf, int off, int size) {
        if (!ready(rec)) return;
        Sonic sonic = getSonic(rec);
        if (sonic == null) return;

        synchronized (sonic) {
            try {
                short[] tmp = new short[size];
                System.arraycopy(buf, off, tmp, 0, size);
                sonic.writeShortToStream(tmp, size);
                int n = sonic.readShortFromStream(tmp, size);
                if (n <= 0) return;
                if (n > size) n = size;
                for (int i = 0; i < n; i++) {
                    buf[off + i] = tmp[i];
                }
            } catch (Throwable t) {
                XposedBridge.log("FV dsp short: " + t);
            }
        }
    }

    private static void processBytes(AudioRecord rec, byte[] buf, int off, int size) {
        if (!ready(rec)) return;
        Sonic sonic = getSonic(rec);
        if (sonic == null) return;

        int nShorts = size / 2;
        if (nShorts <= 0) return;

        synchronized (sonic) {
            try {
                short[] tmp = new short[nShorts];
                for (int i = 0; i < nShorts; i++) {
                    int lo = buf[off + i * 2] & 0xFF;
                    int hi = buf[off + i * 2 + 1];
                    tmp[i] = (short) ((hi << 8) | lo);
                }
                sonic.writeShortToStream(tmp, nShorts);
                int n = sonic.readShortFromStream(tmp, nShorts);
                if (n <= 0) return;
                if (n > nShorts) n = nShorts;
                for (int i = 0; i < n; i++) {
                    short s = tmp[i];
                    buf[off + i * 2] = (byte) (s & 0xFF);
                    buf[off + i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                }
            } catch (Throwable t) {
                XposedBridge.log("FV dsp byte: " + t);
            }
        }
    }
}
