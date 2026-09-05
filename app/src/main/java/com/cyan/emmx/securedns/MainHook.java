package com.cyan.emmx.securedns;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Restores the hidden "Use secure DNS" entry in Microsoft Edge (Chromium) Android settings.
 *
 * Edge's real privacy page is org.chromium.chrome.browser.edge_settings.EdgePrivacySettings,
 * which inflates the Edge-specific XML edge_privacy_preferences_v2 that has no secure_dns entry.
 * The complete secure DNS fragment (org.chromium.chrome.browser.privacy.secure_dns.SecureDnsSettings)
 * and its localized strings are still shipped in the APK, so re-adding one preference that
 * launches that fragment brings the whole feature back.
 *
 * Works both as an LSPosed module and embedded via NPatch (classic Xposed API + assets/xposed_init,
 * no dependency on the module APK's own resources).
 *
 * Edge installs a custom AppComponentFactory whose class loader only becomes visible to the
 * framework after application attach, so the hook is registered lazily: the first candidate
 * loader that can actually resolve Edge classes wins.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "EdgeSecureDNS";

    private static final String[] TARGET_PACKAGES = {
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx",
    };

    /** onCreatePreferences of Edge's real privacy page (verified on Edge Beta 153.0.4234.18). */
    private static final String PRIVACY_FRAGMENT =
            "org.chromium.chrome.browser.edge_settings.EdgePrivacySettings";
    /**
     * Known names of the privacy fragment's onCreatePreferences. The method is R8-renamed in
     * release builds (currently "R1"); these are only fast-path hints — if none matches, the
     * method is located by its (Bundle, String) -> void signature instead.
     */
    private static final String[] PRIVACY_ON_CREATE_CANDIDATES = {"R1", "onCreatePreferences"};

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

    /** String resource ids verified on Edge Beta 153.0.4234.18; content-checked before use. */
    private static final int RES_SECURE_DNS_TITLE = 0x7f14272a;
    private static final int RES_SECURE_DNS_SUMMARY = 0x7f142727;

    private static final String FALLBACK_TITLE_ZH = "使用安全的 DNS";
    private static final String FALLBACK_SUMMARY_ZH = "确定如何通过安全连接来连接到网站";
    private static final String FALLBACK_TITLE_EN = "Use secure DNS";
    private static final String FALLBACK_SUMMARY_EN =
            "Determines how to connect to websites over a secure connection";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!isTarget(lpp.packageName)) return;
        log("loaded into " + lpp.packageName + " (loader=" + loaderName(lpp.classLoader) + ")");

        // Fast path: the default app loader can already resolve Edge classes.
        tryRegister(new ClassLoader[]{lpp.classLoader});

        // Edge swaps in a custom class loader during startup (its AppComponentFactory installs
        // one); at handleLoadPackage time lpp.classLoader may not see Edge classes yet. Retry
        // with the loaders that actually load the Application and each Activity.
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.thisObject;
                            tryRegister(new ClassLoader[]{
                                    app.getClass().getClassLoader(),
                                    ((Context) param.args[0]).getClassLoader(),
                            });
                        }
                    });
            log("application attach retry armed");
        } catch (Throwable t) {
            log("attach hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            tryRegister(new ClassLoader[]{
                                    param.thisObject.getClass().getClassLoader()
                            });
                        }
                    });
            log("activity onCreate retry armed");
        } catch (Throwable t) {
            log("activity hook failed: " + t);
        }
    }

    private static boolean isTarget(String pkg) {
        for (String p : TARGET_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /** Tries each loader; the first one able to resolve Edge's privacy fragment gets hooked. */
    private static void tryRegister(ClassLoader[] candidates) {
        if (REGISTERED.get()) return;
        for (ClassLoader cl : candidates) {
            if (cl == null || REGISTERED.get()) continue;
            Class<?> privacy = findClassQuietly(PRIVACY_FRAGMENT, cl);
            if (privacy == null) continue;
            Method onCreate = findOnCreatePreferences(privacy);
            if (onCreate == null) {
                log("no (Bundle,String)->void method on " + privacy.getName());
                continue;
            }
            if (!REGISTERED.compareAndSet(false, true)) return;
            try {
                XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    inject(param.thisObject, cl);
                                } catch (Throwable t) {
                                    log("inject failed: " + t);
                                    log(t);
                                }
                            }
                        });
                log("hooked " + privacy.getName() + "." + onCreate.getName()
                        + " via loader " + loaderName(cl));
            } catch (Throwable t) {
                REGISTERED.set(false);
                log("hook failed: " + t);
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
    private static Method findOnCreatePreferences(Class<?> privacy) {
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

    private static void inject(Object fragment, ClassLoader registeredLoader) {
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
        String resTitle = safeString(ctx, RES_SECURE_DNS_TITLE, "dns");
        String title = resTitle != null ? resTitle : (zh ? FALLBACK_TITLE_ZH : FALLBACK_TITLE_EN);
        String resSummary = resTitle != null ? safeString(ctx, RES_SECURE_DNS_SUMMARY, null) : null;
        String summary = resSummary != null ? resSummary
                : (zh ? FALLBACK_SUMMARY_ZH : FALLBACK_SUMMARY_EN);

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
            return XposedHelpers.findClassIfExists(name, cl);
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

    private static Object newPreference(Context ctx, ClassLoader cl) {
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
     * Reflection by exact name+signature. XposedHelpers.callMethod must NOT be used on Edge
     * preference objects: it enumerates every declared method (Class.getDeclaredMethods), and
     * Edge's R8 merged an account-module method with an isolated-split parameter type into
     * androidx.preference.Preference, whose resolution throws NoClassDefFoundError. getMethod
     * only resolves parameter types of same-name candidates, which are plain framework types.
     */
    private static void invoke(Object target, String name, Class<?>[] sig, Object... args) {
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
    private static String safeString(Context ctx, int resId, String mustContainLowercase) {
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

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    private static void log(Throwable t) {
        XposedBridge.log("[" + TAG + "] " + android.util.Log.getStackTraceString(t));
    }
}
