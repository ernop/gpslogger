package com.mendhak.gpslogger.common;

import android.content.SharedPreferences;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/**
 * An update must not change what the app does for someone who never changed a setting, unless it tells them.
 * Until a setting is set, its fallback decides what the app does, and a settings screen saves its own default
 * the first time it is shown. So a changed fallback, a new setting whose fallback turns something on, or a
 * screen default that differs from the fallback changes the app quietly.
 *
 * This test lists every fallback the settings code reads and every settings screen default, and fails when the
 * list differs from settings-fallbacks.txt. For a changed fallback, keep the old one. A new setting falls back to
 * what the app did before it existed, and its screen default is the same. Then copy the list the failure names
 * over settings-fallbacks.txt.
 */
public class SettingsFallbacksTest {

    @Test
    public void settingFallbacksAreTheListedOnes() throws Exception {
        String actual = table();
        String expected = resource("/settings-fallbacks.txt");
        if (!actual.equals(expected)) {
            File current = new File("build/settings-fallbacks.txt");
            Files.write(current.toPath(), actual.getBytes(StandardCharsets.UTF_8));
            fail("Settings fallbacks differ from settings-fallbacks.txt (- listed, + now):\n"
                    + differences(expected, actual) + "The current list is " + current.getAbsolutePath());
        }
    }

    private static String table() throws Exception {
        Map<String, Set<String>> fallbacks = codeFallbacks();
        Map<String, String> screens = screenDefaults();
        Set<String> keys = new TreeSet<>(fallbacks.keySet());
        keys.addAll(screens.keySet());
        StringBuilder table = new StringBuilder("# key\tfallbacks in the code\tsettings screen default\n");
        for (String key : keys) {
            Set<String> code = fallbacks.get(key);
            String screen = screens.get(key);
            table.append(key).append('\t')
                    .append(code == null ? "-" : String.join(" | ", code)).append('\t')
                    .append(screen == null ? "-" : screen).append('\n');
        }
        return table.toString();
    }

    /** Calls every getter on PreferenceHelper against preferences that hold nothing, recording each fallback read. */
    private static Map<String, Set<String>> codeFallbacks() throws Exception {
        Map<String, Set<String>> found = new TreeMap<>();
        SharedPreferences prefs = mock(SharedPreferences.class, invocation -> {
            String name = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if (name.startsWith("get") && args.length == 2 && args[0] instanceof String) {
                if (!found.containsKey(args[0])) {
                    found.put((String) args[0], new TreeSet<>());
                }
                found.get(args[0]).add(name.substring(3) + " " + args[1]);
                return args[1];
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });

        Constructor<PreferenceHelper> constructor = PreferenceHelper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        PreferenceHelper helper = constructor.newInstance();
        Field field = PreferenceHelper.class.getDeclaredField("prefs");
        field.setAccessible(true);
        field.set(helper, prefs);

        List<Method> getters = new ArrayList<>();
        for (Method method : PreferenceHelper.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 0 && method.getReturnType() != void.class) {
                getters.add(method);
            }
        }
        Field instance = AppSettings.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object app = instance.get(null);
        instance.set(null, standInApp());
        try {
            for (Method getter : getters) {
                try {
                    getter.invoke(helper);
                } catch (InvocationTargetException e) {
                    // A getter that needs more of the running app stops here; the fallbacks it read first are recorded.
                }
            }
        } finally {
            instance.set(null, app);
        }
        return found;
    }

    /** Fallbacks taken from the app: strings by resource name, folders by what they are. */
    private static AppSettings standInApp() {
        Map<Integer, String> strings = new TreeMap<>();
        for (Field field : com.mendhak.gpslogger.R.string.class.getFields()) {
            try {
                strings.put(field.getInt(null), "@string/" + field.getName());
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        AppSettings[] app = new AppSettings[1];
        app[0] = mock(AppSettings.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "getApplicationContext":
                    return app[0];
                case "getString":
                    return strings.get((Integer) invocation.getArgument(0));
                case "getExternalFilesDir":
                    return new File("/<app external files folder>");
                case "getFilesDir":
                    return new File("/<app files folder>");
                default:
                    return RETURNS_DEFAULTS.answer(invocation);
            }
        });
        return app[0];
    }

    private static Map<String, String> screenDefaults() throws Exception {
        Map<String, String> found = new TreeMap<>();
        File[] screens = new File("src/main/res/xml").listFiles((dir, name) -> name.endsWith(".xml"));
        if (screens == null) {
            fail("No settings screens in " + new File("src/main/res/xml").getAbsolutePath());
        }
        Arrays.sort(screens);
        for (File screen : screens) {
            NodeList elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(screen)
                    .getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                if (element.hasAttribute("android:key") && element.hasAttribute("android:defaultValue")) {
                    found.put(element.getAttribute("android:key"), element.getAttribute("android:defaultValue"));
                }
            }
        }
        return found;
    }

    private static String differences(String expected, String actual) {
        List<String> before = Arrays.asList(expected.split("\n"));
        List<String> after = Arrays.asList(actual.split("\n"));
        StringBuilder lines = new StringBuilder();
        for (String line : before) {
            if (!after.contains(line)) {
                lines.append("- ").append(line).append('\n');
            }
        }
        for (String line : after) {
            if (!before.contains(line)) {
                lines.append("+ ").append(line).append('\n');
            }
        }
        return lines.toString();
    }

    private static String resource(String name) {
        InputStream stream = SettingsFallbacksTest.class.getResourceAsStream(name);
        if (stream == null) {
            return "";
        }
        try (Scanner scanner = new Scanner(stream, "UTF-8").useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }
}
