package com.cyan.emmx.securedns;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Restores the hidden "Use secure DNS" entry in Microsoft Edge (Chromium) Android settings.
 *
 * Edge's real privacy page is org.chromium.chrome.browser.edge_settings.EdgePrivacySettings,
 * which inflates the Edge-specific XML edge_privacy_preferences_v2 that has no secure_dns entry.
 * The complete secure DNS fragment (org.chromium.chrome.browser.privacy.secure_dns.SecureDnsSettings)
 * and its localized strings are still shipped in the APK, so re-adding one preference that
 * launches that fragment brings the whole feature back.
 *
 * Built against the modern Xposed API (libxposed API 102); requires a framework that implements
 * it (minApiVersion=101 in META-INF/xposed/module.prop). Legacy de.robv APIs are not used.
 *
 * Edge installs a custom AppComponentFactory whose class loader differs from the default one.
 * The modern lifecycle covers this directly: onPackageLoaded offers the default loader, and
 * onPackageReady fires after the AppComponentFactory has instantiated its class loader, so the
 * Edge loader is visible without the Application.attach / Activity.onCreate retry ladder the
 * legacy API needed. An Activity.onCreate retry is still armed as a last resort for builds
 * whose privacy fragment only becomes loadable later.
 */
public class MainHook extends XposedModule {

    private static final String TAG = "EdgeSecureDNS";

    private static final String[] TARGET_PACKAGES = {
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx",
            "com.microsoft.emmx.canary",
    };

    /** onCreatePreferences of Edge's real privacy page (verified: Edge Beta 153 / Canary 154). */
    private static final String PRIVACY_FRAGMENT =
            "org.chromium.chrome.browser.edge_settings.EdgePrivacySettings";
    /**
     * Known names of the privacy fragment's onCreatePreferences. The method is R8-renamed in
     * release builds ("R1" on Beta 153, "T1" on Canary 154); these are only fast-path hints —
     * if none matches, the method is located by its (Bundle, String) -> void signature instead.
     */
    private static final String[] PRIVACY_ON_CREATE_CANDIDATES = {
            "T1", "R1", "onCreatePreferences"};

    /** First loadable wins; later candidates cover older/newer Chromium layouts. */
    private static final String[] SECURE_DNS_FRAGMENT_CANDIDATES = {
            "org.chromium.chrome.browser.privacy.secure_dns.SecureDnsSettings",
            "org.chromium.chrome.browser.settings.privacy.SecureDnsOptionsFragment",
            "org.chromium.chrome.browser.settings.SecureDnsSettingsFragment",
    };

    /** Edge-styled first, Chromium-styled second, plain androidx as last resort. */
    private static final String[] PREFERENCE_IMPLS = {
            "com.microsoft.edge.dewey.settings.DeweyPreference",
            "org.chromium.components.browser_ui.settings.ChromeBasePreference",
            "androidx.preference.Preference",
    };

    private static final String KEY_SECURE_DNS = "secure_dns";
    private static final String KEY_SECURITY_CATEGORY = "security";
    private static final int ORDER_IN_CATEGORY = 5;

    /**
     * Secure DNS title/summary string ids, verified per build (all content-checked at runtime;
     * resource ids shift between Edge releases, so every known build gets its own pair).
     */
    private static final int[][] RES_SECURE_DNS_IDS = {
            {0x7f14272a, 0x7f142727}, // Edge Beta 153.0.4234.18
            {0x7f142785, 0x7f142782}, // Edge Canary 154.0.4257.0
    };

    private static final String FALLBACK_TITLE_ZH = "使用安全的 DNS";
    private static final String FALLBACK_SUMMARY_ZH = "确定如何通过安全连接来连接到网站";
    private static final String FALLBACK_TITLE_EN = "Use secure DNS";
    private static final String FALLBACK_SUMMARY_EN =
            "Determines how to connect to websites over a secure connection";

    /** Per module generation; hooks from a previous generation are unhooked on hot reload. */
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private HookHandle activityHook;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log("loaded into " + param.getProcessName()
                + ", framework " + getFrameworkName() + " " + getFrameworkVersion()
                + ", api " + getApiVersion());
    }

    /** Default class loader ready; Edge's AppComponentFactory has not run yet. */
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!isTarget(param.getPackageName())) return;
        log("onPackageLoaded " + param.getPackageName()
                + " (default loader=" + loaderName(param.getDefaultClassLoader()) + ")");
        tryRegister(new ClassLoader[]{param.getDefaultClassLoader()});
    }

    /**
     * AppComponentFactory has instantiated the class loader; for Edge this is the custom loader
     * that was invisible at load time under the legacy API.
     */
    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!isTarget(param.getPackageName())) return;
        log("onPackageReady " + param.getPackageName()
                + " (loader=" + loaderName(param.getClassLoader()) + ")");
        tryRegister(new ClassLoader[]{param.getClassLoader(), param.getDefaultClassLoader()});

        if (!registered.get() && activityHook == null) {
            armActivityRetry();
        }
    }

    /** Last-resort retry: re-check on every Activity creation until registration succeeds. */
    private void armActivityRetry() {
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            activityHook = hook(onCreate).intercept(chain -> {
                try {
                    tryRegister(new ClassLoader[]{
                            chain.getThisObject().getClass().getClassLoader()});
                } catch (Throwable t) {
                    log("activity retry failed: " + t);
                }
                chain.proceed();
                return null;
            });
            log("activity onCreate retry armed");
        } catch (Throwable t) {
            log("cannot arm activity hook: " + t);
        }
    }

    private void unhookActivityRetry() {
        HookHandle handle = activityHook;
        if (handle != null) {
            activityHook = null;
            try {
                handle.unhook();
                log("activity onCreate retry unhooked");
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isTarget(String pkg) {
        for (String p : TARGET_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /** Tries each loader; the first one able to resolve Edge's privacy fragment gets hooked. */
    private void tryRegister(ClassLoader[] candidates) {
        if (registered.get()) return;
        for (ClassLoader cl : candidates) {
            if (cl == null || registered.get()) continue;
            Class<?> privacy = findClassQuietly(PRIVACY_FRAGMENT, cl);
            if (privacy == null) continue;
            Method onCreate = findOnCreatePreferences(privacy);
            if (onCreate == null) {
                log("no (Bundle,String)->void method on " + privacy.getName());
                continue;
            }
            if (!registered.compareAndSet(false, true)) return;
            try {
                hook(onCreate).intercept(chain -> {
                    chain.proceed();
                    try {
                        inject(chain.getThisObject(), cl);
                    } catch (Throwable t) {
                        log("inject failed");
                        log(t);
                    }
                    return null;
                });
                log("hooked " + privacy.getName() + "." + onCreate.getName()
                        + " via loader " + loaderName(cl));
                unhookActivityRetry();
            } catch (Throwable t) {
                registered.set(false);
                log("hook failed");
                log(t);
            }
            return;
        }
    }

    /**
     * Locates onCreatePreferences on the privacy fragment: known names first (no method
     * enumeration), then a signature scan over the fragment's own declared methods. Only the
     * fragment class is enumerated — its declared methods use resolvable base-APK types, unlike
     * androidx.preference.Preference which Edge's R8 polluted with isolated-split types.
     */
    private Method findOnCreatePreferences(Class<?> privacy) {
        for (String name : PRIVACY_ON_CREATE_CANDIDATES) {
            try {
                return privacy.getDeclaredMethod(name, Bundle.class, String.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            for (Method m : privacy.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == Bundle.class && p[1] == String.class
                        && m.getReturnType() == void.class) {
                    return m;
                }
            }
        } catch (Throwable t) {
            log("signature scan failed on " + privacy.getName() + ": " + t);
        }
        return null;
    }

    private void inject(Object fragment, ClassLoader registeredLoader) {
        // Edge loads parts of its code through a custom class loader (isolated splits); the
        // fragment instance itself was created by the loader that can see all of it, so prefer
        // that one for every lookup below.
        ClassLoader cl = fragment.getClass().getClassLoader();
        if (cl == null) cl = registeredLoader;
        log("injecting, fragment loader=" + loaderName(cl));

        Object screen = PrefCompat.getPreferenceScreen(fragment);
        if (screen == null) {
            log("no PreferenceScreen on privacy fragment");
            return;
        }

        Object existing = PrefCompat.findPreference(screen, KEY_SECURE_DNS);
        if (existing != null) {
            invoke(existing, "setVisible", new Class<?>[]{boolean.class}, true);
            log("secure_dns already present, ensured visible");
            return;
        }

        String dnsFragment = firstLoadable(SECURE_DNS_FRAGMENT_CANDIDATES, cl);
        if (dnsFragment == null) {
            log("secure DNS fragment class not found");
            return;
        }

        Context ctx = contextOf(fragment);
        if (ctx == null) {
            log("no context on privacy fragment");
            return;
        }

        Object pref = newPreference(ctx, cl);
        if (pref == null) {
            log("cannot instantiate any preference impl");
            return;
        }

        boolean zh = "zh".equals(localeLang(ctx));
        String title = null;
        String summary = null;
        for (int[] ids : RES_SECURE_DNS_IDS) {
            String t = safeString(ctx, ids[0], "dns");
            if (t == null) continue;
            title = t;
            summary = safeString(ctx, ids[1], null);
            break;
        }
        if (title == null) {
            title = zh ? FALLBACK_TITLE_ZH : FALLBACK_TITLE_EN;
            summary = zh ? FALLBACK_SUMMARY_ZH : FALLBACK_SUMMARY_EN;
        }

        invoke(pref, "setKey", new Class<?>[]{String.class}, KEY_SECURE_DNS);
        invoke(pref, "setPersistent", new Class<?>[]{boolean.class}, false);
        invoke(pref, "setOrder", new Class<?>[]{int.class}, ORDER_IN_CATEGORY);
        invoke(pref, "setTitle", new Class<?>[]{CharSequence.class}, title);
        invoke(pref, "setSummary", new Class<?>[]{CharSequence.class}, summary);
        invoke(pref, "setFragment", new Class<?>[]{String.class}, dnsFragment);

        boolean added = false;
        Object category = PrefCompat.findPreference(screen, KEY_SECURITY_CATEGORY);
        if (category != null && PrefCompat.isGroup(category)) {
            added = PrefCompat.addToGroup(category, pref, KEY_SECURE_DNS);
        }
        if (!added) {
            added = PrefCompat.addToGroup(screen, pref, KEY_SECURE_DNS);
        }
        log(added
                ? "secure_dns entry injected (fragment=" + dnsFragment + ")"
                : "failed to add secure_dns entry");
    }

    private static Class<?> findClassQuietly(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstLoadable(String[] names, ClassLoader cl) {
        for (String name : names) {
            if (findClassQuietly(name, cl) != null) return name;
        }
        return null;
    }

    private Object newPreference(Context ctx, ClassLoader cl) {
        for (String name : PREFERENCE_IMPLS) {
            Class<?> cls = findClassQuietly(name, cl);
            if (cls == null) continue;
            try {
                return cls.getConstructor(Context.class, android.util.AttributeSet.class)
                        .newInstance(ctx, null);
            } catch (Throwable t) {
                log("constructor failed for " + name + ": " + t);
            }
        }
        return null;
    }

    private static Context contextOf(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("requireContext");
            return (Context) m.invoke(fragment);
        } catch (Throwable ignored) {
        }
        try {
            Method m = fragment.getClass().getMethod("getActivity");
            return (Context) m.invoke(fragment);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Reflection by exact name+signature. CallMethod-style enumeration must NOT be used on Edge
     * preference objects: enumerating every declared method (Class.getDeclaredMethods) fails on
     * Edge's R8-merged androidx.preference.Preference, whose isolated-split parameter types throw
     * NoClassDefFoundError. getMethod only resolves parameter types of same-name candidates,
     * which are plain framework types.
     */
    private void invoke(Object target, String name, Class<?>[] sig, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, sig);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (NoSuchMethodException e) {
            log("method not found: " + name + " on " + target.getClass().getName());
        } catch (Throwable t) {
            log("invoke " + name + " failed: " + t);
        }
    }

    /** Returns the resource string, or null when the id is missing/looks wrong for this build. */
    private static String safeString(Context ctx, int resId,
                                     String mustContainLowercase) {
        try {
            String s = ctx.getString(resId);
            if (s == null || s.isEmpty()) return null;
            if (mustContainLowercase != null
                    && !s.toLowerCase(java.util.Locale.US).contains(mustContainLowercase)) {
                return null;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String localeLang(Context ctx) {
        try {
            java.util.Locale l = ctx.getResources().getConfiguration().getLocales().get(0);
            return l == null ? "en" : l.getLanguage();
        } catch (Throwable t) {
            return "en";
        }
    }

    private static String loaderName(ClassLoader cl) {
        return cl == null ? "null" : cl.getClass().getName();
    }

    private void log(String msg) {
        log(Log.INFO, TAG, msg);
    }

    private void log(Throwable t) {
        log(Log.ERROR, TAG, t.getClass().getName(), t);
    }
}
