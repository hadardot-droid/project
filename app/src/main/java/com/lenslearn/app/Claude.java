package com.lenslearn.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Calls the Anthropic Messages API. Run off the main thread. */
public final class Claude {
    public static final String MODEL = "claude-sonnet-5-5";

    public static final class ApiError extends Exception {
        public final int status;
        ApiError(int status, String msg) { super(msg); this.status = status; }
    }

    static String fields(Lang l) {
        return "Return ONLY a JSON object, no prose, no code fences:\n" +
            "{\"found\":true,\n" +
            " \"he\":\"the object's everyday Hebrew name, singular, without niqqud\",\n" +
            " \"target\":\"the word in " + l.en + " in its native script; include the article if learners normally learn nouns with one (el/la, der/die/das, le/la, il/la, o/a)\",\n" +
            " \"translit\":\"pronunciation for an Israeli reader in simple English letters, syllables split by hyphens, the stressed syllable in CAPITALS\",\n" +
            " \"roman\":\"standard romanization (pinyin with tone marks, Hepburn romaji, Revised Romanization, standard Arabic/Russian/Hindi transliteration); empty string if " + l.en + " uses Latin letters\",\n" +
            " \"heRead\":\"the pronunciation written in Hebrew letters\",\n" +
            " \"gender\":\"grammatical gender in Hebrew (זכר / נקבה / סתמי), or empty string if none\",\n" +
            " \"ex\":\"a very short, simple sentence in " + l.en + " using the word\",\n" +
            " \"exTr\":\"that sentence's pronunciation in simple English letters\",\n" +
            " \"exHe\":\"that sentence in Hebrew\",\n" +
            " \"confidence\":\"high|medium|low\",\n" +
            " \"alts\":[{\"he\":\"another possible Hebrew name\",\"target\":\"its " + l.en + " word\"}]}\n" +
            "alts: 0 to 2 other likely identifications, only when unsure.";
    }

    public static JSONObject identify(String key, Lang l, String jpegBase64) throws Exception {
        String prompt = "You help a Hebrew speaker learn " + l.en + " by naming things they photograph. " +
            "Identify the single most prominent everyday object in the photo (ignore the background; if the main subject is a person, " +
            "name a visible object or clothing item instead and never identify the person). " +
            "If nothing nameable is visible, return {\"found\":false,\"why\":\"short reason in Hebrew\"}.\n" + fields(l);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
            .put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64)));
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        return ask(key, content);
    }

    public static JSONObject translate(String key, Lang l, String hebrew) throws Exception {
        String prompt = "A Hebrew speaker learning " + l.en + " photographed an object whose Hebrew name is \"" +
            hebrew.replace("\"", "") + "\". Trust this name (fix only obvious spelling) and give its " + l.en + " word.\n" + fields(l);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        return ask(key, content);
    }

    private static JSONObject ask(String key, JSONArray content) throws Exception {
        JSONObject body = new JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", key);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String txt = read(in);
        c.disconnect();
        if (status >= 400) {
            String msg = txt;
            try { msg = new JSONObject(txt).getJSONObject("error").optString("message", txt); } catch (Exception ignored) { }
            throw new ApiError(status, msg);
        }
        JSONArray parts = new JSONObject(txt).getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if ("text".equals(p.optString("type"))) sb.append(p.optString("text"));
        }
        String t = sb.toString();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b < a) throw new ApiError(0, "bad answer");
        return new JSONObject(t.substring(a, b + 1));
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
