package me.meiser79.intercomsatellite;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** English UI texts for engine tests, read from the real default resource file (no Android Context needed). */
final class TestMessages extends Messages {
    private static final Map<Integer, String> TEXTS = load();
    private static final Map<Integer, String[]> PLURALS = loadPlurals();

    TestMessages() {
        super(null);
    }

    @Override
    String get(int id, Object... args) {
        String text = TEXTS.get(id);
        if (text == null) throw new IllegalStateException("No English text for string id " + id);
        return args.length == 0 ? text : String.format(text, args);
    }

    @Override
    String plural(int id, int count, Object... args) {
        String[] forms = PLURALS.get(id);
        if (forms == null) throw new IllegalStateException("No English plural for id " + id);
        return String.format(count == 1 ? forms[0] : forms[1], args);
    }

    private static String xml() throws Exception {
        return new String(
                Files.readAllBytes(Path.of("src/main/res/values/strings.xml")),
                StandardCharsets.UTF_8);
    }

    /** English "one" and "other" forms by id. */
    private static Map<Integer, String[]> loadPlurals() {
        try {
            String xml = xml();
            Map<Integer, String[]> byId = new HashMap<>();
            for (Field field : R.plurals.class.getFields()) {
                Matcher m =
                        Pattern.compile(
                                        "<plurals name=\""
                                                + field.getName()
                                                + "\">\\s*<item quantity=\"one\">(.*?)</item>\\s*"
                                                + "<item quantity=\"other\">(.*?)</item>",
                                        Pattern.DOTALL)
                                .matcher(xml);
                if (m.find()) byId.put(field.getInt(null), new String[] {m.group(1), m.group(2)});
            }
            return byId;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<Integer, String> load() {
        try {
            String xml = xml();
            Map<String, String> byName = new HashMap<>();
            Matcher m =
                    Pattern.compile("<string name=\"([^\"]+)\">(.*?)</string>", Pattern.DOTALL)
                            .matcher(xml);
            while (m.find()) {
                byName.put(
                        m.group(1),
                        m.group(2).replace("\\n", "\n").replace("&amp;", "&"));
            }
            Map<Integer, String> byId = new HashMap<>();
            for (Field field : R.string.class.getFields()) {
                String text = byName.get(field.getName());
                if (text != null) byId.put(field.getInt(null), text);
            }
            return byId;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
