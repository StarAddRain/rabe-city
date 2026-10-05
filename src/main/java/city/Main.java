package city;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public final class Main {
  final String role, csrf = secret();
  final Cloud cloud;
  final Client client;
  final Curator curator;
  final Properties config;
  final Path web;
  final HttpServer server;

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("prepare")) {
      prepare(
          Paths.get(args.length > 1 ? args[1] : "config/local"),
          args.length > 2 ? args[2] : "http://127.0.0.1:8080",
          args.length > 3 ? Integer.parseInt(args[3]) : 16,
          args.length > 4 ? Integer.parseInt(args[4]) : 8,
          args.length > 5 ? args[5] : "http://127.0.0.1:8083");
      return;
    }
    if (args.length < 1) {
      System.out.println(
          "Usage: java -jar rabe-city.jar config/cloud.properties | prepare config/local http://B_IP:8080 16 8 http://D_IP:8083");
      return;
    }
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
      p.load(in);
    }
    new Main(p);
  }

  Main(Properties p) throws Exception {
    config = p;
    role = p.getProperty("role");
    if (!Arrays.asList("cloud", "owner", "user", "curator").contains(role))
      throw new IllegalArgumentException("role");
    int port = Integer.parseInt(p.getProperty("port"));
    Path data = Paths.get(p.getProperty("data")), params = Paths.get("parameters.properties");
    web = Paths.get("web").toAbsolutePath().normalize();
    if (role.equals("curator")) {
      curator = new Curator(p, params);
      cloud = null;
      client = null;
    } else if (role.equals("cloud")) {
      cloud = new Cloud(p, params);
      curator = null;
      client = null;
    } else {
      client = new Client(p, params);
      cloud = null;
      curator = null;
    }
    server =
        HttpServer.create(
            new InetSocketAddress(
                p.getProperty(
                    "bind",
                    (role.equals("cloud") || role.equals("curator")) ? "0.0.0.0" : "127.0.0.1"),
                port),
            0);
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.createContext("/", this::handle);
    server.start();
    System.out.println(
        "RABE CITY "
            + role.toUpperCase(Locale.ROOT)
            + " http://localhost:"
            + port
            + " | data="
            + data.toAbsolutePath());
  }

  void handle(HttpExchange e) throws IOException {
    try {
      String path = e.getRequestURI().getPath();
      if (path.startsWith("/node/")) {
        if (cloud == null && curator == null)
          throw new SecurityException("No node API on this endpoint");
        if (!e.getRequestMethod().equals("POST"))
          throw new IllegalArgumentException("POST required");
        String auth = e.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
        String r = cloud != null ? cloud.authenticate(token) : curator.authenticate(token);
        reply(
            e,
            200,
            cloud != null
                ? cloud.api(path.substring(5), body(e), r)
                : curator.api(path.substring(5), body(e), r));
        return;
      }
      if (path.startsWith("/api/")) {
        // Local dashboards are intentionally local-only; remote hosts use authenticated /node APIs.
        if (!e.getRemoteAddress().getAddress().isLoopbackAddress())
          throw new SecurityException("请在该电脑本机打开 localhost 控制台");
      }
      if (path.equals("/api/state")) {
        if (!e.getRequestMethod().equals("GET")) throw new IllegalArgumentException("GET required");
        Map<String, Object> state;
        if (curator != null) {
          try {
            state =
                Client.request(
                    config.getProperty("cloud") + "/node/state",
                    config.getProperty("token"),
                    Json.obj());
          } catch (Exception ex) {
            state = emptyState();
            state.put("connectionError", ex.getMessage());
          }
          Map<String, Object> local = curator.localState();
          Set<Integer> leaked = new HashSet<>();
          for (Object o : Json.list(state.get("vehicles")))
            if (Boolean.TRUE.equals(Json.map(o).get("leaked")))
              leaked.add(Json.num(Json.map(o), "id"));
          state.putAll(local);
          for (Object o : Json.list(state.get("vehicles")))
            Json.map(o).put("leaked", leaked.contains(Json.num(Json.map(o), "id")));
        } else state = cloud != null ? cloud.state() : client.state();
        state.put("role", role);
        state.put("csrf", csrf);
        reply(e, 200, state);
        return;
      }
      if (path.equals("/api/motion")) {
        if (!e.getRequestMethod().equals("GET")) throw new IllegalArgumentException("GET required");
        reply(
            e,
            200,
            cloud != null
                ? cloud.motion.snapshot()
                : Client.request(
                    config.getProperty("cloud") + "/node/motion",
                    config.getProperty("token"),
                    Json.obj()));
        return;
      }
      if (path.equals("/api/inbox")) {
        if (!e.getRequestMethod().equals("GET") || client == null)
          throw new SecurityException("仅 C 本机车辆收件箱");
        String q = e.getRequestURI().getRawQuery();
        if (q == null || !q.matches("vehicle=[0-9]+"))
          throw new IllegalArgumentException("请指定 vehicle");
        reply(e, 200, client.inbox(Integer.parseInt(q.substring(8))));
        return;
      }
      if (path.startsWith("/api/")) {
        if (!e.getRequestMethod().equals("POST")
            || !csrf.equals(e.getRequestHeaders().getFirst("X-Local-Token")))
          throw new SecurityException("Invalid local request");
        String origin = e.getRequestHeaders().getFirst("Origin"),
            host = e.getRequestHeaders().getFirst("Host");
        if (origin != null && !origin.equals("http://" + host))
          throw new SecurityException("Cross origin request forbidden");
        Map<String, Object> b = body(e);
        Object result;
        if (curator != null) result = curator.local(path.substring(4), b);
        else if (cloud != null) {
          if (!path.equals("/api/trace") && !path.equals("/api/motion/set"))
            throw new SecurityException("B 只提供云端操作");
          result = cloud.api(path.substring(4), b, "cloud");
        } else result = client.api(path.substring(4), b);
        reply(e, 200, result);
        return;
      }
      if (!e.getRequestMethod().equals("GET")) {
        reply(e, 405, Json.obj("error", "GET required"));
        return;
      }
      Path file = web.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
      if (!file.startsWith(web) || !Files.isRegularFile(file)) {
        reply(e, 404, Json.obj("error", "Not found"));
        return;
      }
      byte[] data = Files.readAllBytes(file);
      String resourcePath = path.toLowerCase(Locale.ROOT);
      String mime =
          resourcePath.endsWith(".js")
              ? "text/javascript"
              : resourcePath.endsWith(".css")
                  ? "text/css"
                  : resourcePath.endsWith(".svg")
                      ? "image/svg+xml"
                      : resourcePath.endsWith(".png")
                          ? "image/png"
                          : resourcePath.endsWith(".webp")
                              ? "image/webp"
                              : resourcePath.endsWith(".jpg") || resourcePath.endsWith(".jpeg")
                                  ? "image/jpeg"
                                  : "text/html";
      String contentType =
          mime.startsWith("text/") || mime.equals("image/svg+xml")
              ? mime + "; charset=utf-8"
              : mime;
      e.getResponseHeaders().set("Content-Type", contentType);
      e.getResponseHeaders()
          .set(
              "Content-Security-Policy",
              "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
      e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      e.sendResponseHeaders(200, data.length);
      try (OutputStream out = e.getResponseBody()) {
        out.write(data);
      }
    } catch (SecurityException ex) {
      reply(e, 403, Json.obj("error", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      reply(e, 400, Json.obj("error", ex.getMessage()));
    } catch (IllegalStateException ex) {
      reply(e, 409, Json.obj("error", ex.getMessage()));
    } catch (Exception ex) {
      System.err.println(ex.toString());
      reply(e, 503, Json.obj("error", ex.getMessage() == null ? "服务暂不可用" : ex.getMessage()));
    } finally {
      e.close();
    }
  }

  Map<String, Object> body(HttpExchange e) throws IOException {
    String ct = e.getRequestHeaders().getFirst("Content-Type");
    if (ct == null || !ct.startsWith("application/json"))
      throw new IllegalArgumentException("JSON content type required");
    return Json.map(
        Json.read(
            new String(readLimited(e.getRequestBody(), 64 * 1024 * 1024), StandardCharsets.UTF_8)));
  }

  static byte[] readLimited(InputStream input, int max) throws IOException {
    if (input == null) throw new IOException("Empty response");
    try (InputStream in = input;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int read, total = 0;
      while ((read = in.read(buf)) != -1) {
        total += read;
        if (total > max) throw new IOException("Request too large");
        out.write(buf, 0, read);
      }
      return out.toByteArray();
    }
  }

  static void reply(HttpExchange e, int status, Object o) throws IOException {
    byte[] bytes = Json.write(o).getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    e.getResponseHeaders().set("Cache-Control", "no-store");
    e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    e.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = e.getResponseBody()) {
      out.write(bytes);
    }
  }

  static String secret() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static Map<String, Object> emptyState() {
    return Json.obj(
        "vehicles",
        new ArrayList<>(),
        "messages",
        new ArrayList<>(),
        "samples",
        new ArrayList<>(),
        "events",
        new ArrayList<>(),
        "nodes",
        Json.obj(),
        "ready",
        false);
  }

  static void prepare(Path dir, String cloudUrl, int n, int u, String curatorUrl) throws Exception {
    if (n < 2 || n > 128 || u < 5 || u > 50)
      throw new IllegalArgumentException("capacity 2..128; attributes 5..50");
    Files.createDirectories(dir);
    for (String r : Arrays.asList("cloud", "owner", "user", "curator"))
      if (Files.exists(dir.resolve(r + ".properties")))
        throw new IllegalStateException("配置已存在，拒绝覆盖令牌");
    String owner = secret(), user = secret(), cloud = secret(), curator = secret();
    for (String r : Arrays.asList("cloud", "owner", "user", "curator")) {
      Properties p = new Properties();
      p.setProperty("role", r);
      p.setProperty(
          "port",
          r.equals("cloud")
              ? "8080"
              : r.equals("owner") ? "8081" : r.equals("user") ? "8082" : "8083");
      p.setProperty("bind", r.equals("cloud") || r.equals("curator") ? "0.0.0.0" : "127.0.0.1");
      p.setProperty("data", "runtime/" + r);
      p.setProperty("cloud", cloudUrl);
      p.setProperty("curator", curatorUrl);
      p.setProperty(
          "token",
          r.equals("cloud")
              ? cloud
              : r.equals("curator") ? curator : r.equals("owner") ? owner : user);
      if (r.equals("curator") || r.equals("cloud")) {
        p.setProperty("ownerToken", owner);
        p.setProperty("userToken", user);
      }
      if (r.equals("cloud")) p.setProperty("curatorToken", curator);
      if (r.equals("curator")) {
        p.setProperty("cloudToken", cloud);
        p.setProperty("vehicles", "" + n);
        p.setProperty("attributes", "" + u);
      }
      try (OutputStream out = Files.newOutputStream(dir.resolve(r + ".properties"))) {
        p.store(out, "RABE City private node configuration");
      }
    }
    System.out.println(
        "Prepared "
            + dir
            + "; A=owner, B=cloud, C=user, D=curator. Copy only the corresponding config to each computer.");
  }
}
