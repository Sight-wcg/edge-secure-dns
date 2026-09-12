package com.cyan.emmx.securedns;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * Gives Edge Beta/Stable the same "load a local extension (.crx)" developer entry that
 * Canary ships, without hard-coding any R8-renamed symbol.
 *
 * Canary's DeveloperSettings source method contains a block that inserts three Extension
 * developer rows while the extension gate reports enabled; that block is absent from the
 * Beta/Stable build of the same method even though the whole extension subsystem (dev
 * activities, manifest declarations, native install path, feature flag) is present. This
 * class re-adds one plain androidx.preference.Preference row to the retained-name
 * DeveloperSettings screen and launches Edge's own, already-declared
 * ExtensionInstallByCrxActivity (identical in all channels) when the row is tapped.
 *
 * Anchors (all preserved across Edge releases, verified Beta 153 / Stable 151):
 *  - DeveloperSettings keeps its Chromium class name. Its row-building method is located by
 *    walking the real superclass chain for the first (Bundle,String)->void declaration.
 *    R8 gives every override of a virtual family the same name (the abstract
 *    PreferenceFragmentCompat root is obfuscated but the override name is shared), so the
 *    method found this way is the one the framework actually calls.
 *  - Taps are handled by hooking the retained-name androidx.preference.Preference.onClick()
 *    (androidx invokes it for any row without a click listener/fragment) and filtering on our
 *    row key. This avoids the R8-renamed OnPreferenceClickListener interface entirely.
 *  - The extension gate method (Beta "dwd", Stable "yrd", …) is never named: the stable
 *    Chromium JNI anchor class J.N.Z(int) is hooked and, when a call arrives from a
 *    structurally gate-shaped class (abstract, direct Object superclass, no instance fields,
 *    the calling method is static ()Z), that method is forced to true. Discovery stays armed
 *    so any remaining sibling helpers are covered whenever they first run.
 */
final class ExtensionUnlock {

    private static final String TAG = "EdgeSecureDNS";

    static final String PKG_BETA = "com.microsoft.emmx.beta";
    static final String PKG_STABLE = "com.microsoft.emmx";

    private static final String DEVELOPER_SETTINGS =
            "org.chromium.chrome.browser.tracing.settings.DeveloperSettings";
    private static final String CRX_ACTIVITY =
            "com.microsoft.edge.extensions.developer.ExtensionInstallByCrxActivity";
    private static final String PREFERENCE = "androidx.preference.Preference";

    /** ChromeTabbedActivity keeps its Chromium class name; its per-Intent handlers are renamed. */
    private static final String CHROME_TABBED_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final String EXTENSION_STATE_CONTROLLER =
            "com.microsoft.edge.extensions.GlobalExtensionStateController";
    private static final String JNI_CLASS = "J.N";

    /** Stable action/extra strings Edge's own ExtensionInstallByCrxActivity uses after copying
     *  the picked .crx (verified Beta 153 / Canary 154): it starts ChromeTabbedActivity with the
     *  CRX path in EXTENSION_CRX. Canary then installs from that intent; Beta only checks the
     *  action and never reaches the native install stub, which is the gap this dispatch fills. */
    private static final String CRX_INSTALL_ACTION =
            "com.microsoft.edge.extensions.ACTION_INSTALL_EXTENSION_FOR_DEV_MODE";
    private static final String CRX_INSTALL_EXTRA =
            "com.microsoft.edge.extensions.EXTENSION_CRX";

    private static final String KEY = "edge_load_local_extension";
    private static final String EXTRA_TITLE = "install_by_crx_title";
    private static final String EXTRA_BUTTON = "install_by_crx_button_text";

    /** First int parameter of the J.N native dispatch stub: the extension-install command id. */
    private static final int CRX_INSTALL_COMMAND = 1;

    private final XposedModule mod;
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean clickArmed = new AtomicBoolean(false);
    private final AtomicBoolean gateArmed = new AtomicBoolean(false);
    private final AtomicBoolean crxDispatchArmed = new AtomicBoolean(false);
    private final Set<Method> forcedGates = new HashSet<Method>();

    /** CRX install intents already handled, by identity (Intent does not override equals). */
    private final Set<Intent> dispatchedCrx =
            Collections.newSetFromMap(new IdentityHashMap<Intent, Boolean>());

    ExtensionUnlock(XposedModule mod) {
        this.mod = mod;
    }

    static boolean targets(String pkg) {
        return PKG_BETA.equals(pkg) || PKG_STABLE.equals(pkg);
    }

    boolean isRegistered() {
        return registered.get();
    }

    /** Installs the DeveloperSettings hook through the first loader that can see it. */
    boolean tryRegister(ClassLoader[] loaders) {
        if (registered.get()) return true;
        for (ClassLoader cl : loaders) {
            if (cl == null || registered.get()) continue;
            Class<?> dev = load(DEVELOPER_SETTINGS, cl);
            if (dev == null) continue;
            Method build = findBuildMethod(dev);
            if (build == null) {
                log("no (Bundle,String)->void on " + dev.getName());
                continue;
            }
            if (!registered.compareAndSet(false, true)) return true;
            try {
                mod.hook(build).intercept(chain -> {
                    try {
                        armClickHook(cl);
                    } catch (Throwable t) {
                        log("arm click hook failed");
                        log(t);
                    }
                    try {
                        armGateDiscovery(cl);
                    } catch (Throwable t) {
                        log("arm gate discovery failed");
                        log(t);
                    }
                    try {
                        armCrxDispatch(cl);
                    } catch (Throwable t) {
                        log("arm crx dispatch failed");
                        log(t);
                    }
                    chain.proceed();
                    try {
                        inject(chain.getThisObject());
                    } catch (Throwable t) {
                        log("developer row inject failed");
                        log(t);
                    }
                    return null;
                });
                log("extension hook armed on " + dev.getName() + "." + build.getName());
                return true;
            } catch (Throwable t) {
                registered.set(false);
                log("developer-settings hook failed");
                log(t);
            }
        }
        return false;
    }

    /**
     * The method the framework calls to let DeveloperSettings build its rows: locate the
     * (Bundle,String)->void virtual family on the real superclass chain, then hook the concrete
     * DeveloperSettings override. If a class declares more than one candidate, refuse to guess.
     */
    private Method findBuildMethod(Class<?> dev) {
        Method direct = findUniqueSettingsMethod(dev);
        if (direct != null) return direct;
        for (Class<?> c = dev.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Method inherited = findUniqueSettingsMethod(c);
            if (inherited == null) continue;
            try {
                return dev.getDeclaredMethod(inherited.getName(), Bundle.class, String.class);
            } catch (Throwable t) {
                log("no DeveloperSettings override for " + c.getName() + "." + inherited.getName());
                return null;
            }
        }
        return null;
    }

    private Method findUniqueSettingsMethod(Class<?> c) {
        Method found = null;
        int count = 0;
        try {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == Bundle.class && p[1] == String.class
                        && m.getReturnType() == void.class) {
                    found = m;
                    count++;
                }
            }
        } catch (Throwable ignored) {
        }
        if (count > 1) {
            log("ambiguous (Bundle,String)->void on " + c.getName() + ": " + count);
            return null;
        }
        return found;
    }

    /** Reacts to taps on our row: Preference.performClick() calls onClick() for any enabled
     *  selectable row (no listener/fragment needed), and the name is retained by androidx. */
    private void armClickHook(ClassLoader cl) {
        if (!clickArmed.compareAndSet(false, true)) return;
        try {
            Class<?> pref = load(PREFERENCE, cl);
            if (pref == null) return;
            Method onClick = pref.getDeclaredMethod("onClick");
            mod.hook(onClick).intercept(c -> {
                try {
                    Object p = c.getThisObject();
                    if (p != null && KEY.equals(invoke(p, "getKey"))) {
                        startCrx(p);
                    }
                } catch (Throwable t) {
                    log("Preference.onClick interceptor");
                    log(t);
                }
                return c.proceed();
            });
            log("Preference.onClick hook armed");
        } catch (Throwable t) {
            clickArmed.set(false);
            log("cannot arm Preference.onClick");
            log(t);
        }
    }

    /**
     * Discovers the extension gate without naming it: Chromium's generated JNI stub class
     * J.N keeps its name, and every Edge build's gate calls J.N.Z(int). A call with the
     * extension feature id coming from a gate-shaped class lets us find the method and force
     * it true (the hook stays armed so siblings are covered when they first run).
     */
    private void armGateDiscovery(ClassLoader cl) {
        if (!gateArmed.compareAndSet(false, true)) return;
        try {
            Class<?> jni = Class.forName("J.N", false, cl);
            Method z = jni.getDeclaredMethod("Z", int.class);
            if (z.getReturnType() != boolean.class) {
                gateArmed.set(false);
                log("J.N.Z has an unexpected signature");
                return;
            }
            mod.hook(z).intercept(c -> {
                try {
                    Object a = c.getArg(0);
                    if (a instanceof Integer && ((Integer) a).intValue() == 1) {
                        Method gate = findGateShapedCaller(cl);
                        if (gate != null) {
                            forceTrue(gate);
                            return Boolean.TRUE;
                        }
                    }
                } catch (Throwable t) {
                    log("J.N.Z interceptor");
                    log(t);
                }
                return c.proceed();
            });
            log("J.N.Z gate discovery armed");
        } catch (Throwable t) {
            gateArmed.set(false);
            log("cannot arm J.N.Z");
            log(t);
        }
    }

    /** The extension gate method: abstract class, direct Object superclass, no instance
     *  fields, and the currently executing method is static ()Z. */
    private static Method findGateShapedCaller(ClassLoader cl) {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (StackTraceElement f : frames) {
            String cn = f.getClassName();
            String mn = f.getMethodName();
            if (cn == null || mn == null || cn.isEmpty() || mn.startsWith("<")) continue;
            if (isNoise(cn)) continue;
            try {
                Class<?> c = Class.forName(cn, false, cl);
                if (!Modifier.isAbstract(c.getModifiers())) continue;
                if (c.getSuperclass() != Object.class) continue;
                if (hasInstanceFields(c)) continue;
                Method m = c.getDeclaredMethod(mn);
                if (!Modifier.isStatic(m.getModifiers())
                        || m.getParameterCount() != 0
                        || m.getReturnType() != boolean.class) continue;
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean hasInstanceFields(Class<?> c) {
        try {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isNoise(String cn) {
        return cn.startsWith("io.github.libxposed")
                || cn.startsWith("de.robv.android.xposed")
                || cn.startsWith("com.cyan.emmx.securedns")
                || cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.")
                || cn.startsWith("android.") || cn.startsWith("dalvik.") || cn.startsWith("libcore.")
                || cn.startsWith("sun.") || cn.startsWith("com.android.");
    }

    /**
     * Re-adds the native CRX install step that Canary performs inside its ChromeTabbedActivity
     * intent handler but Beta/Stable dropped (they recognize ACTION_INSTALL_EXTENSION_FOR_DEV_MODE,
     * then fall through into the ordinary intent flow without ever calling the J.N install stub).
     *
     * Canary's handler is a ChromeTabbedActivity-declared (Intent)->void method with a TraceEvent
     * string naming it "onNewIntentWithNative" (Beta "Y1", Canary "c2"); the name is per-build so
     * we hook the whole small family of such handlers on ChromeTabbedActivity and act only when the
     * stable CRX action arrives, which only the real handler ever sees. Non-CRX intents always
     * proceed untouched.
     */
    private void armCrxDispatch(ClassLoader cl) {
        if (!crxDispatchArmed.compareAndSet(false, true)) return;
        try {
            Class<?> cta = load(CHROME_TABBED_ACTIVITY, cl);
            if (cta == null) {
                crxDispatchArmed.set(false);
                log("ChromeTabbedActivity not loadable");
                return;
            }
            int hooked = 0;
            for (Method m : cta.getDeclaredMethods()) {
                if (!isIntentHandlerShape(m)) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                try {
                    mod.hook(m).intercept(c -> {
                        try {
                            Object a0 = c.getArg(0);
                            if (a0 instanceof Intent) {
                                Intent it = (Intent) a0;
                                if (CRX_INSTALL_ACTION.equals(it.getAction())) {
                                    Object self = c.getThisObject();
                                    ClassLoader cl2 = self == null ? cl : self.getClass().getClassLoader();
                                    if (dispatchCrxInstall(it, self, cl2)) return null;
                                }
                            }
                        } catch (Throwable t) {
                            log("crx intent interceptor");
                            log(t);
                        }
                        return c.proceed();
                    });
                    hooked++;
                } catch (Throwable t) {
                    log("cannot hook " + cta.getSimpleName() + "." + m.getName());
                    log(t);
                }
            }
            if (hooked == 0) {
                crxDispatchArmed.set(false);
                log("no (Intent)->void handler on ChromeTabbedActivity");
                return;
            }
            log("crx dispatch armed on " + hooked + " CTA handler(s)");
        } catch (Throwable t) {
            crxDispatchArmed.set(false);
            log("cannot arm crx dispatch");
            log(t);
        }
    }

    /** Handles the CRX install intent the way Canary does and returns true when it consumed it. */
    private boolean dispatchCrxInstall(Intent intent, Object owner, ClassLoader cl) {
        if (!dispatchedCrx.add(intent)) {
            // Same intent instance observed twice (e.g. several CTA handlers hooked); first one
            // already dispatched, treat the repeat as consumed.
            return true;
        }
        String crxPath = intent.getStringExtra(CRX_INSTALL_EXTRA);
        if (crxPath == null || crxPath.isEmpty()) {
            log("crx intent without EXTENSION_CRX");
            dispatchedCrx.remove(intent);
            return false;
        }
        Context ctx = owner instanceof Context ? (Context) owner : null;
        if (ctx == null || !isTrustedCrxPath(ctx, crxPath)) {
            log("rejecting untrusted crx path: " + crxPath);
            dispatchedCrx.remove(intent);
            return true;
        }
        try {
            if (cl == null) {
                log("no class loader for crx dispatch");
                dispatchedCrx.remove(intent);
                return false;
            }
            enablePostponedExtensions(cl);
            Method install = findInstallStub(cl);
            if (install == null) {
                log("J.N install stub not found (unique (int,Object)->void native)");
                dispatchedCrx.remove(intent);
                return false;
            }
            install.invoke(null, CRX_INSTALL_COMMAND, crxPath);
            log("crx install dispatched: " + crxPath);
            return true;
        } catch (Throwable t) {
            log("crx install dispatch failed");
            log(t);
            dispatchedCrx.remove(intent);
            return false;
        }
    }

    private static boolean isTrustedCrxPath(Context ctx, String crxPath) {
        try {
            File base = ctx.getExternalFilesDir("extensions");
            if (base == null) return false;
            File baseDir = base.getCanonicalFile();
            File crx = new File(crxPath).getCanonicalFile();
            if (!crx.isFile()) return false;
            if (!crx.getName().toLowerCase(Locale.US).endsWith(".crx")) return false;
            String basePath = baseDir.getPath();
            String crxPathCanonical = crx.getPath();
            return crxPathCanonical.startsWith(basePath + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True for the per-build named (Intent)->void handlers ChromeTabbedActivity uses to process
     * incoming intents once native is ready (Beta Y1 / Canary c2 and their helpers).
     */
    private static boolean isIntentHandlerShape(Method m) {
        if (Modifier.isStatic(m.getModifiers())) return false;
        if (Modifier.isPrivate(m.getModifiers())) return false;
        if (m.getReturnType() != void.class) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 1 && p[0] == Intent.class;
    }

    /**
     * Mirrors Canary: mark postponed extension components for loading. The controller class is
     * retained; its static (boolean)->void setter is R8-renamed per build, located by signature.
     */
    private void enablePostponedExtensions(ClassLoader cl) {
        Class<?> c = load(EXTENSION_STATE_CONTROLLER, cl);
        if (c == null) {
            log("GlobalExtensionStateController not loadable");
            return;
        }
        Method setter = null;
        int count = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == boolean.class) {
                setter = m;
                count++;
            }
        }
        if (count != 1) {
            log("GlobalExtensionStateController static (boolean)->void count=" + count);
            return;
        }
        try {
            setter.setAccessible(true);
            setter.invoke(null, Boolean.TRUE);
        } catch (Throwable t) {
            log("cannot enable postponed extensions");
            log(t);
        }
    }

    /**
     * The J.N native stub for the CRX install. J.N is Chromium's generated JNI dispatcher; its
     * method names encode the JVM signature deterministically (V + I + O = (int,Object)->void),
     * so there is exactly one such declaration per build and locating it by signature never needs
     * the per-build name. The first int argument selects the native function (install = 1).
     */
    private static Method findInstallStub(ClassLoader cl) {
        Class<?> jni = load(JNI_CLASS, cl);
        if (jni == null) return null;
        Method found = null;
        for (Method m : jni.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!Modifier.isNative(m.getModifiers())) continue;
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2 || p[0] != int.class || p[1] != Object.class) continue;
            if (found != null) return null; // ambiguous, refuse to guess
            found = m;
        }
        return found;
    }

    private void forceTrue(Method gate) {
        if (!forcedGates.add(gate)) return;
        try {
            mod.hook(gate).intercept(c -> Boolean.TRUE);
            log("forced extension gate "
                    + gate.getDeclaringClass().getName() + "." + gate.getName());
        } catch (Throwable t) {
            log("cannot force gate " + gate);
            log(t);
        }
    }

    private void inject(Object fragment) {
        Object screen = PrefCompat.getPreferenceScreen(fragment);
        if (screen == null) {
            log("no PreferenceScreen on DeveloperSettings");
            return;
        }
        if (PrefCompat.findPreference(screen, KEY) != null) return;

        Context ctx = contextOf(fragment);
        if (ctx == null) {
            log("no context on DeveloperSettings");
            return;
        }

        ClassLoader cl = fragment.getClass().getClassLoader();
        Class<?> prefCls = load(PREFERENCE, cl);
        if (prefCls == null) {
            log("androidx.preference.Preference not loadable");
            return;
        }
        Object pref = newPreference(ctx, prefCls);
        if (pref == null) {
            log("cannot instantiate Preference");
            return;
        }

        String title = moduleString(ctx, R.string.load_local_extension_title, "Load local extension");
        String summary = moduleString(ctx, R.string.load_local_extension_summary,
                "Install an extension from a local .crx file (developer option)");

        invoke(pref, "setKey", new Class<?>[]{String.class}, KEY);
        invoke(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
        invoke(pref, "setSummary", new Class<?>[]{CharSequence.class}, summary);
        invoke(pref, "setPersistent", new Class<?>[]{boolean.class}, false);

        boolean added = PrefCompat.addToGroup(screen, pref, KEY);
        log(added ? "load-local-extension row injected" : "failed to add load-local-extension row");
    }

    private void startCrx(Object pref) {
        try {
            // Make sure the CRX install-intent dispatch is armed before the picker returns to
            // ChromeTabbedActivity; the tap can be the first moment ChromeTabbedActivity's classes
            // are reachable, so retry here with the Preference's loader if the build-time arm
            // (DeveloperSettings hook) had not fired yet.
            ClassLoader prefLoader = pref.getClass().getClassLoader();
            if (prefLoader != null) armCrxDispatch(prefLoader);

            Context ctx = preferenceContext(pref);
            if (ctx == null) {
                log("no context for starting crx activity");
                return;
            }

            boolean zh = localeZh(ctx);
            String title = moduleString(ctx, R.string.load_local_extension_title, "Load local extension");
            String choose = moduleString(ctx, R.string.load_local_extension_choose_crx,
                    zh ? "选择 .crx 文件" : "Choose .crx file");

            // Edge ships as split APKs and the extension dev activity lives in a split that is
            // not loaded while Beta/Stable has no extension entry, so the class may not be on any
            // visible class loader yet. The manifest declares the activity (it exists in every
            // channel), so start by component name: Android resolves it and SplitCompat loads the
            // owning split before the activity launches.
            Intent it = new Intent();
            it.setClassName(ctx.getPackageName(), CRX_ACTIVITY);
            it.putExtra(EXTRA_TITLE, title);
            it.putExtra(EXTRA_BUTTON, choose);
            if (!(ctx instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            log("starting ExtensionInstallByCrxActivity by component");
        } catch (Throwable t) {
            log("start crx failed");
            log(t);
        }
    }

    private static Context preferenceContext(Object pref) {
        try {
            Method m = pref.getClass().getMethod("getContext");
            Object c = m.invoke(pref);
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context contextOf(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("requireContext");
            Object c = m.invoke(fragment);
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        try {
            Method m = fragment.getClass().getMethod("getActivity");
            Object c = m.invoke(fragment);
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object newPreference(Context ctx, Class<?> prefCls) {
        try {
            return prefCls.getConstructor(Context.class, android.util.AttributeSet.class)
                    .newInstance(ctx, null);
        } catch (Throwable t) {
            log("Preference constructor failed: " + t);
            return null;
        }
    }

    /** Returns the module string for the current locale, falling back to a literal. */
    private String moduleString(Context hostCtx, int resId, String fallback) {
        try {
            Context self = hostCtx.createPackageContext(
                    mod.getModuleApplicationInfo().packageName, 0);
            String s = self.getString(resId);
            return (s == null || s.isEmpty()) ? fallback : s;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean localeZh(Context ctx) {
        try {
            Locale l = ctx.getResources().getConfiguration().getLocales().get(0);
            return l != null && "zh".equals(l.getLanguage());
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invoke(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private void invoke(Object target, String name, Class<?>[] sig, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, sig);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (Throwable t) {
            log("invoke " + name + " failed: " + t);
        }
    }

    private static Class<?> load(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private void log(String msg) {
        log(Log.INFO, TAG, msg);
    }

    private void log(Throwable t) {
        log(Log.ERROR, TAG, t.getClass().getName(), t);
    }

    private void log(int level, String tag, String msg) {
        mod.log(level, tag, msg);
    }

    private void log(int level, String tag, String msg, Throwable t) {
        mod.log(level, tag, msg, t);
    }
}
