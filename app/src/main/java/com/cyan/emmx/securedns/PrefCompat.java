package com.cyan.emmx.securedns;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * androidx.preference internals are R8-renamed inside Edge (PreferenceFragmentCompat becomes a
 * short class, PreferenceGroup too); only the class names androidx.preference.Preference and
 * androidx.preference.PreferenceScreen survive. Framework entry points are therefore located by
 * signature instead of by method name.
 */
final class PrefCompat {

    private static final String PREF = "androidx.preference.Preference";
    private static final String SCREEN = "androidx.preference.PreferenceScreen";

    private PrefCompat() {
    }

    /** Zero-arg method on the fragment hierarchy returning PreferenceScreen (getPreferenceScreen). */
    static Object getPreferenceScreen(Object fragment) {
        for (Class<?> c = fragment.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && SCREEN.equals(m.getReturnType().getName())) {
                    try {
                        m.setAccessible(true);
                        return m.invoke(fragment);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * The PreferenceGroup class of this node: the class in its hierarchy whose superclass is
     * Preference. For both PreferenceScreen instances and category instances this resolves to the
     * (renamed) androidx PreferenceGroup class.
     */
    private static Class<?> groupClass(Object node) {
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Class<?> s = c.getSuperclass();
            if (s != null && PREF.equals(s.getName())) return c;
        }
        return null;
    }

    static boolean isGroup(Object node) {
        return groupClass(node) != null;
    }

    static Object findPreference(Object group, String key) {
        for (Method m : groupMethods(group)) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == CharSequence.class
                    && PREF.equals(m.getReturnType().getName())) {
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(group, (Object) key);
                    if (r != null) return r;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Adds a preference by trying every single-Preference-parameter method of the group class
     * (addPreference / removePreference etc., names renamed by R8). A failed trial is a no-op for
     * a not-yet-attached preference, so trying candidates until findPreference(key) hits is safe.
     */
    static boolean addToGroup(Object group, Object pref, String key) {
        for (Method m : groupMethods(group)) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !PREF.equals(p[0].getName())) continue;
            try {
                m.setAccessible(true);
                m.invoke(group, pref);
            } catch (Throwable ignored) {
                continue;
            }
            if (findPreference(group, key) != null) return true;
        }
        return false;
    }

    private static List<Method> groupMethods(Object node) {
        List<Method> out = new ArrayList<Method>();
        Class<?> gc = groupClass(node);
        if (gc != null) {
            for (Method m : gc.getDeclaredMethods()) out.add(m);
        }
        return out;
    }
}
