package com.localdrop.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public class I18n {
    private static final String BUNDLE_BASE_NAME = "com.localdrop.i18n.messages";

    private AppLanguage language;
    private ResourceBundle bundle;

    public I18n(AppLanguage language) {
        setLanguage(language);
    }

    public void setLanguage(AppLanguage language) {
        this.language = language;
        this.bundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, language.toLocale());
    }

    public AppLanguage getLanguage() {
        return language;
    }

    public String text(String key) {
        return bundle.getString(key);
    }

    public String format(String key, Object... args) {
        MessageFormat format = new MessageFormat(text(key), bundle.getLocale() == null ? Locale.getDefault() : bundle.getLocale());
        return format.format(args);
    }
}
