import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Campus Lost & Found - Java backend that stores everything in Supabase.
 * Needs Java 11 or newer and no libraries.
 *
 * Environment variables:
 *   SUPABASE_URL  e.g. https://abcdxyz.supabase.co
 *   SUPABASE_KEY  the "service_role" secret key (keep it secret!)
 *   ADMIN_KEY     the host password for the Suggestion box inbox
 *   PORT          set automatically by Render (default 8080)
 */
public class LostFoundServer {

    static final String ADMIN_KEY = env("ADMIN_KEY", "admin123");
    static final int PORT = Integer.parseInt(env("PORT", "8080"));
    static final String SB_URL = env("SUPABASE_URL", "").replaceAll("/+$", "");
    static final String SB_KEY = env("SUPABASE_KEY", "");

    static final HttpClient http = HttpClient.newHttpClient();
    static final Object LOCK = new Object();
    // Small cache so the page's 3-second refresh does not hit Supabase every time.
    static final Map<String, List<Map<String, String>>> cache = new HashMap<>();
    static final Map<String, Long> fetchedAt = new HashMap<>();
    static final List<String> COLLECTIONS = List.of("items", "claims", "suggestions");

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    public static void main(String[] args) throws Exception {
        if (SB_URL.isEmpty() || SB_KEY.isEmpty()) {
            System.err.println("Set the SUPABASE_URL and SUPABASE_KEY environment variables first.");
            System.exit(1);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", LostFoundServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Campus Lost & Found is running on port " + PORT);
    }

    // ---------- Routing ----------

    static void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();
            if (method.equals("OPTIONS")) { // browser permission check (CORS) from the GitHub Pages site
                send(x, 200, "text/plain", new byte[0]);
                return;
            }
            if (path.equals("/healthz")) {
                send(x, 200, "text/plain", "ok".getBytes(UTF_8));
                return;
            }
            if (path.equals("/") && method.equals("GET")) {
                send(x, 200, "text/html; charset=utf-8", Files.readAllBytes(Paths.get("public", "index.html")));
                return;
            }
            String[] seg = path.split("/"); // ["", "api", collection, id, action]
            if (seg.length >= 3 && seg[1].equals("api") && COLLECTIONS.contains(seg[2])) {
                api(x, method, seg);
                return;
            }
            send(x, 404, "text/plain", "Not found".getBytes(UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
            json(x, 500, "{\"error\":\"Server error. Please try again.\"}");
        }
    }

    static void api(HttpExchange x, String method, String[] seg) throws IOException {
        String coll = seg[2];
        boolean admin = ADMIN_KEY.equals(x.getRequestHeaders().getFirst("X-Admin-Key"));
        Map<String, String> query = parseForm(x.getRequestURI().getRawQuery());
        String by = query.getOrDefault("by", "");

        synchronized (LOCK) {
            List<Map<String, String>> list = records(coll);

            // List records
            if (seg.length == 3 && method.equals("GET")) {
                if (coll.equals("suggestions") && !admin) {
                    json(x, 403, "{\"error\":\"Only the host can read suggestions\"}");
                    return;
                }
                StringBuilder sb = new StringBuilder("[");
                for (int i = list.size() - 1; i >= 0; i--) {
                    Map<String, String> r = list.get(i);
                    Map<String, String> view = new LinkedHashMap<>(r);
                    boolean show = admin || by.equals(r.get("createdBy"));
                    if (coll.equals("claims")) {
                        Map<String, String> item = find(records("items"), r.get("itemId"));
                        show = show || (item != null && by.equals(item.get("createdBy")));
                    }
                    if (!show) { // hide private contact details from other people
                        view.remove("contact");
                        view.remove("message");
                    }
                    sb.append(toJson(view));
                    if (i > 0) sb.append(",");
                }
                json(x, 200, sb.append("]").toString());
                return;
            }

            // Add a record
            if (seg.length == 3 && method.equals("POST")) {
                Map<String, String> r = parseForm(new String(x.getRequestBody().readAllBytes(), UTF_8));
                r.put("id", UUID.randomUUID().toString());
                r.put("createdAt", String.valueOf(System.currentTimeMillis()));
                if (coll.equals("items")) {
                    r.put("status", "open");
                    savePhoto(r);
                }
                insert(coll, r);
                json(x, 200, toJson(r));
                return;
            }

            // Change or delete one record
            if (seg.length >= 4) {
                Map<String, String> item = find(list, seg[3]);
                if (item == null) {
                    json(x, 404, "{\"error\":\"Not found\"}");
                    return;
                }
                boolean allowed = admin || by.equals(item.get("createdBy"));
                if (!allowed) {
                    json(x, 403, "{\"error\":\"Only the person who posted this can change it\"}");
                    return;
                }
                if (method.equals("DELETE")) {
                    if (coll.equals("items")) {
                        for (Map<String, String> c : new ArrayList<>(records("claims"))) {
                            if (seg[3].equals(c.get("itemId"))) remove("claims", c.get("id"));
                        }
                        deletePhoto(item.get("photo"));
                    }
                    remove(coll, seg[3]);
                    json(x, 200, "{\"ok\":true}");
                    return;
                }
                if (method.equals("POST") && seg.length == 5 && seg[4].equals("return")) {
                    Map<String, String> f = parseForm(new String(x.getRequestBody().readAllBytes(), UTF_8));
                    item.put("status", "returned");
                    item.put("returnedTo", f.getOrDefault("to", ""));
                    item.put("returnedId", f.getOrDefault("pid", ""));
                    item.put("returnedAt", LocalDate.now().toString());
                    update(coll, item);
                    json(x, 200, toJson(item));
                    return;
                }
            }
            json(x, 400, "{\"error\":\"Bad request\"}");
        }
    }

    // ---------- Supabase storage (database + photos) ----------

    // Calls the Supabase REST API with the secret key.
    static String supabase(String method, String path, String type, byte[] body, String prefer) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(SB_URL + path))
                .header("apikey", SB_KEY)
                .header("Authorization", "Bearer " + SB_KEY);
        if (type != null) b.header("Content-Type", type);
        if (prefer != null) b.header("Prefer", prefer);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
            if (r.statusCode() >= 300) throw new IOException("Supabase " + r.statusCode() + ": " + r.body());
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    // Reads all rows of a table (cached for 1.5 seconds). Each row keeps its data in one "rec" text field.
    static List<Map<String, String>> records(String coll) throws IOException {
        long now = System.currentTimeMillis();
        Long t = fetchedAt.get(coll);
        if (t != null && now - t < 1500) return cache.get(coll);
        String body = supabase("GET", "/rest/v1/" + coll + "?select=id,created_at,rec&order=created_at.asc", null, null, null);
        List<Map<String, String>> list = new ArrayList<>();
        for (Map<String, String> row : parseRows(body)) list.add(parseForm(row.get("rec")));
        cache.put(coll, list);
        fetchedAt.put(coll, now);
        return list;
    }

    static void insert(String coll, Map<String, String> r) throws IOException {
        try {
            String j = "[{\"id\":\"" + esc(r.get("id")) + "\",\"created_at\":" + r.get("createdAt")
                    + ",\"rec\":\"" + esc(encode(r)) + "\"}]";
            supabase("POST", "/rest/v1/" + coll, "application/json", j.getBytes(UTF_8), "return=minimal");
        } finally {
            fetchedAt.remove(coll);
        }
    }

    static void update(String coll, Map<String, String> r) throws IOException {
        try {
            String j = "{\"rec\":\"" + esc(encode(r)) + "\"}";
            supabase("PATCH", "/rest/v1/" + coll + "?id=eq." + r.get("id"), "application/json", j.getBytes(UTF_8), "return=minimal");
        } finally {
            fetchedAt.remove(coll);
        }
    }

    static void remove(String coll, String id) throws IOException {
        try {
            supabase("DELETE", "/rest/v1/" + coll + "?id=eq." + id, null, null, null);
        } finally {
            fetchedAt.remove(coll);
        }
    }

    // The photo arrives as a data URL; upload it to the public "photos" bucket in Supabase Storage.
    static void savePhoto(Map<String, String> r) {
        String photo = r.remove("photo");
        if (photo == null || !photo.startsWith("data:image") || !photo.contains(",")) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(photo.substring(photo.indexOf(',') + 1));
            if (bytes.length > 3_000_000) return;
            String file = r.get("id") + ".jpg";
            supabase("POST", "/storage/v1/object/photos/" + file, "image/jpeg", bytes, null);
            r.put("photo", SB_URL + "/storage/v1/object/public/photos/" + file);
        } catch (Exception e) {
            System.err.println("Photo upload failed: " + e.getMessage());
        }
    }

    static void deletePhoto(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            supabase("DELETE", "/storage/v1/object/photos/" + url.substring(url.lastIndexOf('/') + 1), null, null, null);
        } catch (Exception e) {
            System.err.println("Photo delete failed: " + e.getMessage());
        }
    }

    // ---------- Helpers ----------

    static Map<String, String> find(List<Map<String, String>> list, String id) {
        for (Map<String, String> r : list) if (r.get("id").equals(id)) return r;
        return null;
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, i), UTF_8), URLDecoder.decode(pair.substring(i + 1), UTF_8));
        }
        return m;
    }

    static String encode(Map<String, String> r) {
        StringJoiner j = new StringJoiner("&");
        for (Map.Entry<String, String> e : r.entrySet()) {
            j.add(URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8));
        }
        return j.toString();
    }

    // Tiny JSON reader for Supabase's answer: a list of flat objects like [{"id":"..","rec":".."}]
    static List<Map<String, String>> parseRows(String s) {
        List<Map<String, String>> rows = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            if (s.charAt(i) != '{') { i++; continue; }
            Map<String, String> row = new LinkedHashMap<>();
            i++;
            while (i < n && s.charAt(i) != '}') {
                if (s.charAt(i) != '"') { i++; continue; }
                int[] pos = {i};
                String key = readString(s, pos);
                i = pos[0];
                while (s.charAt(i) != ':') i++;
                i++;
                while (s.charAt(i) == ' ') i++;
                String val;
                if (s.charAt(i) == '"') {
                    pos[0] = i;
                    val = readString(s, pos);
                    i = pos[0];
                } else {
                    int j = i;
                    while (s.charAt(j) != ',' && s.charAt(j) != '}') j++;
                    val = s.substring(i, j).trim();
                    i = j;
                }
                row.put(key, val);
            }
            rows.add(row);
            i++;
        }
        return rows;
    }

    static String readString(String s, int[] pos) {
        StringBuilder b = new StringBuilder();
        int i = pos[0] + 1;
        while (s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                char d = s.charAt(i);
                if (d == 'n') b.append('\n');
                else if (d == 't') b.append('\t');
                else if (d == 'u') { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                else b.append(d);
            } else {
                b.append(c);
            }
            i++;
        }
        pos[0] = i + 1;
        return b.toString();
    }

    static String toJson(Map<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) b.append(",");
            first = false;
            b.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return b.append("}").toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c < 32 ? ' ' : c);
            }
        }
        return b.toString();
    }

    static void json(HttpExchange x, int code, String body) throws IOException {
        send(x, code, "application/json; charset=utf-8", body.getBytes(UTF_8));
    }

    static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        // Allow the website (hosted on GitHub Pages) to call this server.
        x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Admin-Key");
        x.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        x.sendResponseHeaders(code, body.length);
        x.getResponseBody().write(body);
        x.close();
    }
}
