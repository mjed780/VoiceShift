package com.femaleshift;

import android.media.AudioFormat;
import android.media.AudioRecord;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final AtomicInteger CALLS = new AtomicInteger(0);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null) return;

        hook("read", short[].class, int.class, int.class);
        hook("read", short[].class, int.class, int.class, int.class);
        hook("read", byte[].class, int.class, int.class);
        hook("read", byte[].class, int.class, int.class, int.class);
        hook("read", ByteBuffer.class, int.class);
        hook("read", ByteBuffer.class, int.class, int.class);

        XposedBridge.log("FV: ready in " + lpparam.packageName);
    }

    private static void hook(String name, Class<?>... types) {
        try {
            Object[] a = new Object[types.length + 1];
            for (int i = 0; i < types.length; i++) a[i] = types[i];
            a[types.length] = (types[0] == ByteBuffer.class) ? BB_HOOK : ARRAY_HOOK;
            XposedHelpers.findAndHookMethod(AudioRecord.class, name, a);
            XposedBridge.log("FV: OK hook " + name + "/" + types.length);
        } catch (Throwable t) {
            XposedBridge.log("FV: MISS hook " + name + "/" + types.length + " : " + t);
        }
    }

    private static final XC_MethodHook ARRAY_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                int got = (Integer) param.getResult();
                if (got <= 0) return;
                Object a0 = param.args[0];
                int off = (Integer) param.args[1];
                if (a0 instanceof short[]) {
                    processShorts((AudioRecord) param.thisObject, (short[]) a0, off, got);
                } else if (a0 instanceof byte[]) {
                    if (got < 2) return;
                    processBytes((AudioRecord) param.thisObject, (byte[]) a0, off, got);
                }
            } catch (Throwable t) {
                XposedBridge.log("FV array: " + t);
            }
        }
    };

    private static final XC_MethodHook BB_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                int got = (Integer) param.getResult();
                if (got < 2) return;
                ByteBuffer bb = (ByteBuffer) param.args[0];
                if (bb == null || bb.isReadOnly()) return;
                AudioRecord rec = (AudioRecord) param.thisObject;
                if (!ready(rec)) return;
                int start = bb.position() - got;
                if (start < 0) return;
                int nShorts = got / 2;
                ByteBuffer dup = bb.duplicate();
                short[] tmp = new short[nShorts];
                for (int i = 0; i < nShorts; i++) tmp[i] = dup.getShort(start + i * 2);
                Sonic sonic = getSonic(rec);
                if (sonic == null) return;
                int c = CALLS.incrementAndGet();
                synchronized (sonic) {
                    sonic.writeShortToStream(tmp, nShorts);
                    int n = sonic.readShortFromStream(tmp, nShorts);
                    if (c <= 10) XposedBridge.log("FV: bb#" + c + " in=" + nShorts + " out=" + n);
                    if (n <= 0) return;
                    if (n > nShorts) n = nShorts;
                    for (int i = 0; i < n; i++) dup.putShort(start + i * 2, tmp[i]);
                }
            } catch (Throwable t) {
                XposedBridge.log("FV bb: " + t);
            }
        }
    };

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
                XposedBridge.log("FV: Sonic new sr=" + sr + " ch=" + ch);
            }
            return s;
        }
    }

    private static boolean ready(AudioRecord rec) {
        try {
            return rec.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void processShorts(AudioRecord rec, short[] buf, int off, int size) {
        if (!ready(rec)) return;
        Sonic sonic = getSonic(rec);
        if (sonic == null) return;

        int c = CALLS.incrementAndGet();
        boolean log = (c <= 10) || (c % 1000 == 0);

        synchronized (sonic) {
            try {
                short[] tmp = new short[size];
                System.arraycopy(buf, off, tmp, 0, size);
                sonic.writeShortToStream(tmp, size);
                int n = sonic.readShortFromStream(tmp, size);
                if (log) XposedBridge.log("FV: s#" + c + " in=" + size + " out=" + n);
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

        int c = CALLS.incrementAndGet();
        boolean log = (c <= 10) || (c % 1000 == 0);

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
                if (log) XposedBridge.log("FV: b#" + c + " in=" + nShorts + " out=" + n);
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
