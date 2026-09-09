package amidst.i18n;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import javax.swing.*;
import com.google.gson.*;

/** English fallback with reversible component keys. Never translates editable user input. */
public final class I18n {
    private static volatile Language language = Language.ENGLISH;
    private static boolean installed;
    private static final Map<String,String> CHINESE = load();
    private static final Map<String,String> ENGLISH = new HashMap<>();
    static { CHINESE.forEach((en,zh) -> ENGLISH.putIfAbsent(zh,en)); }
    private static Map<String,String> load() {
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(I18n.class.getResourceAsStream("/amidst/i18n/zh_CN.json")),StandardCharsets.UTF_8)) {
            Map<String,String> result = new LinkedHashMap<>();
            JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e -> result.put(e.getKey(),e.getValue().getAsString()));
            return Collections.unmodifiableMap(result);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public static Language language() { return language; }
    public static String text(String english) {
        if (english == null) return null;
        if (language != Language.SIMPLIFIED_CHINESE) return english;
        String value = CHINESE.get(english);
        if (value != null) return value;
        for (String prefix : new String[]{"World Type: ","Text Seed: ","Numeric Seed: ","Save Game Seed: ","Random Seed: "}) {
            if (english.startsWith(prefix)) return CHINESE.getOrDefault(prefix,prefix) + english.substring(prefix.length());
        }
        return english;
    }
    public static String format(String pattern, Object... arguments) { return MessageFormat.format(text(pattern).replace("'","''"),arguments); }
    public static void install(Language selected) {
        setLanguage(selected);
        if (!installed) {
            installed = true;
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (event instanceof WindowEvent w && w.getID() == WindowEvent.WINDOW_OPENED) {
                    localize(w.getWindow());
                    if (w.getWindow() instanceof JDialog dialog) dialog.pack();
                }
            }, AWTEvent.WINDOW_EVENT_MASK);
        }
    }
    public static void setLanguage(Language selected) {
        language = Objects.requireNonNull(selected);
        JComponent.setDefaultLocale(selected == Language.SIMPLIFIED_CHINESE ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH);
        for (String key : new String[]{"okButtonText","cancelButtonText","yesButtonText","noButtonText"}) {
            String english = switch (key) { case "okButtonText" -> "OK"; case "cancelButtonText" -> "Cancel"; case "yesButtonText" -> "Yes"; default -> "No"; };
            UIManager.put("OptionPane."+key,text(english));
        }
        for (Window window : Window.getWindows()) {
            localize(window);
            if (window instanceof JDialog dialog && dialog.isVisible()) dialog.pack();
            window.invalidate(); window.validate(); window.repaint();
        }
    }
    public static void localize(Component component) {
        if (component instanceof AbstractButton button) button.setText(componentText(button,button.getText()));
        else if (component instanceof JLabel label) label.setText(componentText(label,label.getText()));
        if (component instanceof JDialog dialog) dialog.setTitle(text(ENGLISH.getOrDefault(dialog.getTitle(),dialog.getTitle())));
        if (component instanceof JMenu menu) for (Component child : menu.getMenuComponents()) localize(child);
        else if (component instanceof Container container) for (Component child : container.getComponents()) localize(child);
    }
    private static String componentText(JComponent component, String current) {
        String key = (String) component.getClientProperty("amidst.i18n.key");
        if (key == null || !Objects.equals(current,key) && !Objects.equals(current,component.getClientProperty("amidst.i18n.rendered"))) {
            key = ENGLISH.getOrDefault(current,current); component.putClientProperty("amidst.i18n.key",key);
        }
        String value = text(key); component.putClientProperty("amidst.i18n.rendered",value); return value;
    }
}
