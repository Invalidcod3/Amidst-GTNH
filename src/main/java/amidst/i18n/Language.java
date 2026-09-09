package amidst.i18n;
import java.util.Locale;
public enum Language {
    ENGLISH("English"), SIMPLIFIED_CHINESE("简体中文");
    private final String label;
    Language(String label) { this.label = label; }
    public static Language systemDefault() { return Locale.getDefault().getLanguage().equals("zh") ? SIMPLIFIED_CHINESE : ENGLISH; }
    @Override public String toString() { return label; }
}
