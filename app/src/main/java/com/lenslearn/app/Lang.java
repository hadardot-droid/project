package com.lenslearn.app;

public final class Lang {
    public final String code, he, en, bcp;

    Lang(String code, String he, String en, String bcp) {
        this.code = code; this.he = he; this.en = en; this.bcp = bcp;
    }

    @Override public String toString() { return he; }

    public static final Lang[] ALL = {
        new Lang("en", "אנגלית", "English", "en-US"),
        new Lang("es", "ספרדית", "Spanish", "es-ES"),
        new Lang("fr", "צרפתית", "French", "fr-FR"),
        new Lang("de", "גרמנית", "German", "de-DE"),
        new Lang("it", "איטלקית", "Italian", "it-IT"),
        new Lang("pt", "פורטוגזית", "Portuguese (Brazil)", "pt-BR"),
        new Lang("ru", "רוסית", "Russian", "ru-RU"),
        new Lang("ar", "ערבית", "Arabic (Modern Standard)", "ar-SA"),
        new Lang("zh", "סינית", "Mandarin Chinese (simplified characters)", "zh-CN"),
        new Lang("ja", "יפנית", "Japanese", "ja-JP"),
        new Lang("ko", "קוריאנית", "Korean", "ko-KR"),
        new Lang("hi", "הינדית", "Hindi", "hi-IN"),
    };

    public static Lang of(String code) {
        for (Lang l : ALL) if (l.code.equals(code)) return l;
        return ALL[0];
    }

    public static int indexOf(String code) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].code.equals(code)) return i;
        return 0;
    }
}
