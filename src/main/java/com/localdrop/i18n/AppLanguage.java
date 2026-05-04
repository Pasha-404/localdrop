package com.localdrop.i18n;

import java.util.Locale;

public enum AppLanguage {
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский");

    private final String code;
    private final String displayName;

    AppLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(code);
    }

    public static AppLanguage fromCode(String code) {
        if (code != null) {
            for (AppLanguage language : values()) {
                if (language.code.equalsIgnoreCase(code)) {
                    return language;
                }
            }
        }
        return ENGLISH;
    }

    public static AppLanguage detectDefault() {
        return Locale.getDefault().getLanguage().equalsIgnoreCase(RUSSIAN.code) ? RUSSIAN : ENGLISH;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
