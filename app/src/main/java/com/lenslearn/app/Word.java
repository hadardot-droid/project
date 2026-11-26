package com.lenslearn.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One learned word: what was photographed, in Hebrew and in the target language. */
public final class Word {
    public String id = "", lang = "en", he = "", target = "", translit = "", roman = "", heRead = "",
            gender = "", ex = "", exTr = "", exHe = "", conf = "", img = "";
    public long time;
    public final List<String[]> alts = new ArrayList<>(); // {he, target}, not saved

    static String s(JSONObject o, String k) {
        String v = o.optString(k, "");
        if ("null".equals(v)) v = "";
        v = v.trim();
        return v.length() > 300 ? v.substring(0, 300) : v;
    }

    /** Build from Claude's JSON answer. */
    public static Word fromAnswer(JSONObject o, String lang) {
        Word w = new Word();
        w.lang = lang;
        w.he = s(o, "he"); w.target = s(o, "target"); w.translit = s(o, "translit");
        w.roman = s(o, "roman"); w.heRead = s(o, "heRead"); w.gender = s(o, "gender");
        w.ex = s(o, "ex"); w.exTr = s(o, "exTr"); w.exHe = s(o, "exHe"); w.conf = s(o, "confidence");
        JSONArray a = o.optJSONArray("alts");
        if (a != null) for (int i = 0; i < a.length() && i < 2; i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && !s(x, "he").isEmpty()) w.alts.add(new String[]{s(x, "he"), s(x, "target")});
        }
        w.time = System.currentTimeMillis();
        return w;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("lang", lang); o.put("he", he); o.put("target", target);
            o.put("translit", translit); o.put("roman", roman); o.put("heRead", heRead);
            o.put("gender", gender); o.put("ex", ex); o.put("exTr", exTr); o.put("exHe", exHe);
            o.put("img", img); o.put("time", time);
        } catch (Exception ignored) { }
        return o;
    }

    public static Word fromJson(JSONObject o) {
        Word w = new Word();
        w.id = s(o, "id"); w.lang = s(o, "lang"); w.he = s(o, "he"); w.target = s(o, "target");
        w.translit = s(o, "translit"); w.roman = s(o, "roman"); w.heRead = s(o, "heRead");
        w.gender = s(o, "gender"); w.ex = s(o, "ex"); w.exTr = s(o, "exTr"); w.exHe = s(o, "exHe");
        w.img = s(o, "img"); w.time = o.optLong("time");
        return w;
    }
}
