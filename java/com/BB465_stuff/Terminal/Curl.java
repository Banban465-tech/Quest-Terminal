package com.BB465_stuff.Terminal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * curl, in the app process.
 *
 * This exists because the device cannot do HTTPS with curl. Toybox on the
 * Quest has wget, and wget there does not validate TLS at all (Pkg.java notes
 * the same thing), there is no curl binary, and /system is read-only so one
 * cannot be dropped in. Java already has a correct TLS stack, so the honest
 * replacement is to speak HTTP from Java and print what curl would.
 *
 * It runs on a worker thread and reports through a callback; nothing here
 * touches a View. No Android imports, so this stays unit-testable off-device.
 *
 * Deliberately not implemented: -F multipart, -c/-b cookie jars, --resolve,
 * HTTP/2, and -w write-out. Everything below is what a headset session
 * actually reaches for.
 */
final class Curl {

    /** printed by the caller on whatever thread; the activity hops to the UI */
    interface Say { void say(String s); }

    /** how much of a body is handed to the screen instead of a file */
    static final int MAX_MEM = 512 * 1024;

    private static final int DEFAULT_CONNECT = 15000;
    private static final int DEFAULT_READ = 30000;

    // ------------------------------------------------------------------ opts

    static final class Opts {
        final List<String> urls = new ArrayList<String>();
        final List<String> headers = new ArrayList<String>();
        final List<byte[]> datas = new ArrayList<byte[]>();
        String method;
        String dataType = "application/x-www-form-urlencoded";
        boolean getData;
        String output;
        boolean remoteName;
        boolean head;
        boolean include;
        boolean follow;
        boolean silent;
        boolean showError;
        boolean verbose;
        boolean insecure;
        boolean fail;
        String user;
        String userAgent = "curl/8.7.1 (QuestTerminal)";
        String referer;
        String cookie;
        int connectTimeout = DEFAULT_CONNECT;
        int maxTime;
        String range;
        String proxy;
        File upload;
        boolean help;
        boolean version;
    }

    static final class Weird extends Exception {
        Weird(String m) { super(m); }
    }

    static final class Result {
        int code;
        String status = "";
        String contentType = "";
        final List<String> headers = new ArrayList<String>();
        byte[] body;            // null once streamed to a file
        long bytes;
        boolean truncated;
        String finalUrl = "";
    }

    // ----------------------------------------------------------------- parse

    static Opts parse(String[] a, String cwd) throws Weird {
        Opts o = new Opts();
        List<String> pos = new ArrayList<String>();
        for (int i = 0; i < a.length; i++) {
            String t = a[i];
            if (t.equals("--")) {
                for (i++; i < a.length; i++) pos.add(a[i]);
                break;
            }
            if (t.startsWith("--")) {
                String name = t.substring(2), val = null;
                int eq = name.indexOf('=');
                if (eq >= 0) { val = name.substring(eq + 1); name = name.substring(0, eq); }
                i = longOpt(o, name, val, a, i, cwd);
            } else if (t.length() > 1 && t.charAt(0) == '-') {
                i = shortCluster(o, t, a, i, cwd);
            } else {
                pos.add(t);
            }
        }
        for (String p : pos) o.urls.add(p);
        return o;
    }

    private static final String SHORT_VALUE = "oXHduAebmrxT";

    private static int shortCluster(Opts o, String t, String[] a, int i, String cwd)
            throws Weird {
        for (int k = 1; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (SHORT_VALUE.indexOf(ch) >= 0) {
                String val;
                if (k + 1 < t.length()) val = t.substring(k + 1);
                else {
                    if (i + 1 >= a.length) throw new Weird("option -" + ch + " needs a value");
                    val = a[++i];
                }
                useShortValue(o, ch, val, cwd);
                return i;
            }
            booleanShort(o, ch);
        }
        return i;
    }

    private static void booleanShort(Opts o, char ch) throws Weird {
        switch (ch) {
            case 'O': o.remoteName = true; break;
            case 'I': o.head = true; break;
            case 'i': o.include = true; break;
            case 'L': o.follow = true; break;
            case 's': o.silent = true; break;
            case 'S': o.showError = true; break;
            case 'v': o.verbose = true; break;
            case 'k': o.insecure = true; break;
            case 'f': o.fail = true; break;
            case 'G': o.getData = true; break;
            case 'h': o.help = true; break;
            case '#': break;                 // progress bar, we have nothing to draw
            case '4': case '6': break;       // force IP family
            case 'g': break;                 // globbing off, we never glob
            default: throw new Weird("unknown option -" + ch);
        }
    }

    private static void useShortValue(Opts o, char ch, String val, String cwd)
            throws Weird {
        switch (ch) {
            case 'o': o.output = val; break;
            case 'X': o.method = val; break;
            case 'H': o.headers.add(val); break;
            case 'd': o.datas.add(dataValue(val, cwd)); break;
            case 'u': o.user = val; break;
            case 'A': o.userAgent = val; break;
            case 'e': o.referer = val; break;
            case 'b': o.cookie = val; break;
            case 'm': o.maxTime = seconds(val, "-m"); break;
            case 'r': o.range = val; break;
            case 'x': o.proxy = val; break;
            case 'T': o.upload = fileValue(val, cwd); break;
            default: throw new Weird("unknown option -" + ch);
        }
    }

    private static int longOpt(Opts o, String name, String val, String[] a, int i, String cwd)
            throws Weird {
        if (name.equals("help")) { o.help = true; return i; }
        if (name.equals("version")) { o.version = true; return i; }
        if (name.equals("get")) { o.getData = true; return i; }
        if (name.equals("head")) { o.head = true; return i; }
        if (name.equals("include")) { o.include = true; return i; }
        if (name.equals("location")) { o.follow = true; return i; }
        if (name.equals("silent")) { o.silent = true; return i; }
        if (name.equals("show-error")) { o.showError = true; return i; }
        if (name.equals("verbose")) { o.verbose = true; return i; }
        if (name.equals("insecure")) { o.insecure = true; return i; }
        if (name.equals("fail")) { o.fail = true; return i; }
        if (name.equals("remote-name")) { o.remoteName = true; return i; }

        // accepted and ignored: they change transport details that Java picks
        if (name.equals("compressed") || name.equals("globoff")
                || name.equals("path-as-is") || name.equals("raw")
                || name.equals("progress-bar") || name.equals("no-progress-meter")
                || name.equals("http1.1") || name.equals("http2")
                || name.equals("http2-prior-knowledge") || name.equals("tlsv1.2")
                || name.equals("tlsv1.3")) {
            return i;
        }

        // value options, as --name value or --name=value
        if (name.equals("request") || name.equals("header") || name.equals("data")
                || name.equals("data-raw") || name.equals("data-binary")
                || name.equals("data-ascii") || name.equals("data-urlencode")
                || name.equals("output") || name.equals("user") || name.equals("user-agent")
                || name.equals("referer") || name.equals("cookie") || name.equals("max-time")
                || name.equals("connect-timeout") || name.equals("range")
                || name.equals("proxy") || name.equals("upload-file") || name.equals("url")) {
            if (val == null) {
                if (i + 1 >= a.length) throw new Weird("--" + name + " needs a value");
                val = a[++i];
            }
            if (name.equals("request")) o.method = val;
            else if (name.equals("header")) o.headers.add(val);
            else if (name.equals("data") || name.equals("data-raw")
                    || name.equals("data-binary") || name.equals("data-ascii")
                    || name.equals("data-urlencode")) o.datas.add(dataValue(val, cwd));
            else if (name.equals("output")) o.output = val;
            else if (name.equals("user")) o.user = val;
            else if (name.equals("user-agent")) o.userAgent = val;
            else if (name.equals("referer")) o.referer = val;
            else if (name.equals("cookie")) o.cookie = val;
            else if (name.equals("max-time")) o.maxTime = seconds(val, "--max-time");
            else if (name.equals("connect-timeout")) o.connectTimeout = seconds(val, "--connect-timeout");
            else if (name.equals("range")) o.range = val;
            else if (name.equals("proxy")) o.proxy = val;
            else if (name.equals("upload-file")) o.upload = fileValue(val, cwd);
            else if (name.equals("url")) o.urls.add(val);
            return i;
        }

        throw new Weird("unknown option --" + name);
    }

    private static int seconds(String v, String what) throws Weird {
        try { return Math.max(1, Integer.parseInt(v.trim())); }
        catch (NumberFormatException e) { throw new Weird(what + " wants seconds, not '" + v + "'"); }
    }

    /** -d @file reads the file; the rest is literal */
    private static byte[] dataValue(String v, String cwd) throws Weird {
        if (v.startsWith("@")) {
            String name = v.substring(1);
            if (name.equals("-")) throw new Weird("data from stdin is not supported here");
            return read(fileValue(name, cwd));
        }
        try { return v.getBytes("UTF-8"); }
        catch (Exception e) { return v.getBytes(); }
    }

    private static File fileValue(String name, String cwd) throws Weird {
        File f = new File(name);
        if (!f.isAbsolute()) {
            String base = (cwd == null || cwd.length() == 0) ? "/" : cwd;
            f = new File(base, name);
        }
        return f;
    }

    private static byte[] read(File f) throws Weird {
        try {
            InputStream in = new FileInputStream(f);
            try { return drain(in, f.length()); }
            finally { in.close(); }
        } catch (Exception e) {
            throw new Weird("cannot read " + f + ": " + e.getMessage());
        }
    }

    private static byte[] drain(InputStream in, long hint) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(
                hint > 0 && hint < 1 << 20 ? (int) hint : 8192);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // --------------------------------------------------------------- request

    /**
     * @param sink if non-null the body is streamed there and Result.body is null
     */
    static Result request(Opts o, Say say, File sink) throws Exception {
        if (o.urls.isEmpty()) throw new Weird("no URL given");
        Result res = new Result();

        byte[] data = join(o.datas);
        String url = o.urls.get(0);
        if (o.getData) {
            if (data != null && data.length > 0) {
                url += (url.indexOf('?') >= 0 ? "&" : "?") + new String(data, "UTF-8");
            }
            data = null;
        }

        URL u = new URL(url);
        HttpURLConnection c;
        if (o.proxy != null) {
            c = (HttpURLConnection) u.openConnection(toProxy(o.proxy));
        } else {
            c = (HttpURLConnection) u.openConnection();
        }
        c.setConnectTimeout(o.connectTimeout * 1000);
        c.setReadTimeout((o.maxTime > 0 ? o.maxTime : DEFAULT_READ / 1000) * 1000);
        c.setInstanceFollowRedirects(o.follow);
        c.setRequestProperty("User-Agent", o.userAgent);
        if (o.referer != null) c.setRequestProperty("Referer", o.referer);
        if (o.cookie != null) c.setRequestProperty("Cookie", o.cookie);
        if (o.range != null) c.setRequestProperty("Range", "bytes=" + o.range);
        if (o.user != null) {
            c.setRequestProperty("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(o.user.getBytes("UTF-8")));
        }
        if (o.insecure && c instanceof HttpsURLConnection) {
            HttpsURLConnection h = (HttpsURLConnection) c;
            h.setSSLSocketFactory(trustAll());
            h.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String h2, SSLSession s) { return true; }
            });
        }
        for (String h : o.headers) {
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            String k = h.substring(0, colon).trim();
            String v = h.substring(colon + 1).trim();
            try { c.setRequestProperty(k, v); }
            catch (IllegalArgumentException e) {
                // Host/Content-Length/etc are managed by the stack; curl would
                // send them but Java refuses, so note it rather than crashing
                if (o.verbose) say.say("* java owns the " + k + " header, ignored\n");
            }
        }

        String method;
        if (o.head) method = "HEAD";
        else if (o.method != null) method = o.method.toUpperCase(Locale.US);
        else if (o.upload != null) method = "PUT";
        else if (data != null) method = "POST";
        else method = "GET";
        try {
            c.setRequestMethod(method);
        } catch (java.net.ProtocolException e) {
            // Java's list of methods is smaller than curl's (PATCH is the usual
            // casualty). Poke the field the same way every Android HTTP client
            // does rather than silently downgrading the request to POST.
            try {
                java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
                f.setAccessible(true);
                f.set(c, method);
            } catch (Throwable t) {
                throw e;
            }
        }

        if (o.verbose) {
            say.say("> " + method + " " + url + "\n");
            if (o.userAgent != null) say.say("> User-Agent: " + o.userAgent + "\n");
            for (String h : o.headers) say.say("> " + h + "\n");
            say.say(">\n");
        }

        if (o.upload != null) {
            c.setDoOutput(true);
            if (!hasHeader(o, "Content-Type")) {
                c.setRequestProperty("Content-Type", "application/octet-stream");
            }
            OutputStream os = c.getOutputStream();
            InputStream in = new FileInputStream(o.upload);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally { in.close(); os.close(); }
        } else if (data != null && !method.equals("GET") && !method.equals("HEAD")) {
            c.setDoOutput(true);
            if (!hasHeader(o, "Content-Type")) c.setRequestProperty("Content-Type", o.dataType);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
        }

        int code = c.getResponseCode();
        res.code = code;
        res.status = c.getHeaderField(0);
        if (res.status == null || res.status.length() == 0) {
            res.status = "HTTP " + code + (c.getResponseMessage() == null ? "" : " " + c.getResponseMessage());
        }
        res.contentType = c.getContentType() == null ? "" : c.getContentType();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() == null) continue;          // the status line
            for (String v : e.getValue()) res.headers.add(e.getKey() + ": " + v);
        }
        res.finalUrl = c.getURL() == null ? url : c.getURL().toString();

        if (o.verbose) {
            say.say("< " + res.status + "\n");
            for (String h : res.headers) say.say("< " + h + "\n");
            say.say("<\n");
        }

        if (o.fail && code >= 400) {
            c.disconnect();
            throw new Weird("the server returned " + code);
        }

        if (o.head) {
            res.body = new byte[0];
            c.disconnect();
            return res;
        }

        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) in = new ByteArrayInputStream(new byte[0]);
        try {
            if (sink != null) {
                FileOutputStream fos = new FileOutputStream(sink);
                try {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) { fos.write(buf, 0, n); got += n; }
                    res.bytes = got;
                } finally { fos.close(); }
            } else {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (got < MAX_MEM) {
                        int take = (int) Math.min(n, MAX_MEM - got);
                        bos.write(buf, 0, take);
                    }
                    got += n;
                    if (got >= MAX_MEM) { res.truncated = true; break; }
                }
                res.bytes = got;
                res.body = bos.toByteArray();
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            c.disconnect();
        }
        return res;
    }

    private static boolean hasHeader(Opts o, String name) {
        for (String h : o.headers) {
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static byte[] join(List<byte[]> parts) {
        if (parts.isEmpty()) return null;
        int n = 0;
        for (byte[] p : parts) n += p.length + 1;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(n);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) bos.write('&');
            bos.write(parts.get(i), 0, parts.get(i).length);
        }
        return bos.toByteArray();
    }

    private static Proxy toProxy(String spec) {
        String s = spec;
        if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("https://")) s = s.substring(8);
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            try {
                return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
                        s.substring(0, colon), Integer.parseInt(s.substring(colon + 1))));
            } catch (NumberFormatException ignored) { }
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(s, 8080));
    }

    private static SSLSocketFactory trustAll() throws Exception {
        TrustManager[] tm = new TrustManager[]{ new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tm, new java.security.SecureRandom());
        return sc.getSocketFactory();
    }

    // ------------------------------------------------------------- tokenizer

    /** split a typed line on spaces, honouring single and double quotes */
    static String[] split(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean any = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                else if (ch == '\\' && quote == '"' && i + 1 < line.length()) cur.append(line.charAt(++i));
                else cur.append(ch);
                any = true;
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                any = true;
            } else if (ch == ' ' || ch == '\t') {
                if (any || cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); any = false; }
            } else {
                cur.append(ch);
                any = true;
            }
        }
        if (any || cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[out.size()]);
    }

    static String help() {
        return "curl - HTTP and HTTPS from inside the app\n"
             + "  the device has no curl, and its wget does not check TLS, so\n"
             + "  this one runs in the app where the trust store is real.\n\n"
             + "usage: curl [options] <url>\n"
             + "  -o, --output <file>       write the body to a file\n"
             + "  -O, --remote-name         name the file from the url\n"
             + "  -I, --head                headers only (HEAD)\n"
             + "  -i, --include             show headers before the body\n"
             + "  -L, --location            follow redirects\n"
             + "  -d, --data <data>         body; sends POST. @file reads a file\n"
             + "  -G, --get                 move -d data into the query string\n"
             + "  -X, --request <method>    GET, POST, PUT, DELETE, PATCH ...\n"
             + "  -H, --header <h>          extra header, repeatable\n"
             + "  -T, --upload-file <file>  PUT a local file\n"
             + "  -u, --user <user:pass>    basic auth\n"
             + "  -A, --user-agent <ua>     set the user agent\n"
             + "  -e, --referer <url>       set the referer\n"
             + "  -b, --cookie <data>       send a cookie header\n"
             + "  -r, --range <range>       byte range, e.g. 0-1023\n"
             + "  -x, --proxy <host:port>   use an HTTP proxy\n"
             + "  -m, --max-time <seconds>  overall read timeout\n"
             + "      --connect-timeout <s> connect timeout\n"
             + "  -s, --silent              less chatter\n"
             + "  -v, --verbose             show the request and response lines\n"
             + "  -k, --insecure            skip TLS verification\n"
             + "  -f, --fail                exit on an HTTP error status\n"
             + "  -h, --help                this text\n\n"
             + "examples\n"
             + "  curl https://api.github.com/repos/Banban465-tech/Quest-Terminal\n"
             + "  curl -sSL -o D:\\update.apk <url>\n"
             + "  curl -I https://example.com\n"
             + "  curl -X POST -H 'Content-Type: application/json' -d '{\"a\":1}' <url>\n\n"
             + "saving: an app cannot write /sdcard directly on this target, so\n"
             + "when a shell transport is up the file is staged and handed to it.\n"
             + "without a transport, -o only works under the app's own folders\n"
             + "(cdprefix, or an Android/data path).\n";
    }

    private Curl() { }
}
