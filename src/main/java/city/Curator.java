package city;

import java.nio.file.*;
import java.util.*;
import rabe.*;

/**
 * Registration authority. Only public keys leave A/C. Construction 6.1 uses zero-based slots
 * internally.
 */
public final class Curator {
  final Path data, params;
  final int capacity, universe;
  final Map<String, String> tokens = new HashMap<>();
  final List<Scheme> levels = new ArrayList<>();
  volatile String phase = "生成分层公共参数", error = "";
  volatile Map<String, Object> context, db;

  public Curator(Properties p, Path params) throws Exception {
    this.params = params;
    data = Paths.get(p.getProperty("data"));
    int n = Integer.parseInt(p.getProperty("vehicles", "128"));
    int cap = 1;
    while (cap < n) cap *= 2;
    capacity = cap;
    universe = Integer.parseInt(p.getProperty("attributes", "50"));
    if (n < 2 || n > 128 || universe < 5 || universe > 50)
      throw new IllegalArgumentException("capacity 2..128; attributes 5..50");
    for (String r : Arrays.asList("owner", "user", "cloud"))
      tokens.put(p.getProperty(r + "Token"), r);
    Files.createDirectories(data);
    new Thread(
            () -> {
              try {
                boot();
              } catch (Exception e) {
                error = e.toString();
                phase = "初始化失败";
                e.printStackTrace();
              }
            },
            "curator-setup")
        .start();
  }

  void boot() throws Exception {
    Path manifest = data.resolve("deployment.json");
    Map<String, Object> deployment =
        Files.exists(manifest)
            ? Wire.read(manifest)
            : Json.obj(
                "id", UUID.randomUUID().toString(), "capacity", capacity, "universe", universe);
    if (Json.num(deployment, "capacity") != capacity
        || Json.num(deployment, "universe") != universe)
      throw new IllegalStateException("参数规模已改变，请使用新的 data 目录");
    Wire.save(manifest, deployment);
    List<Object> contexts = new ArrayList<>();
    for (int k = 0; (1 << k) <= capacity; k++) {
      int n = 1 << k;
      phase = "生成/加载第 " + k + " 层（" + n + " 槽位）";
      Path dir = data.resolve("level-" + k);
      Files.createDirectories(dir);
      Path cp = dir.resolve("context.json"), wp = dir.resolve("cross.bin");
      Scheme s;
      Map<String, Object> c;
      if (Files.exists(cp) && Files.exists(wp)) {
        c = Wire.read(cp);
        s = Wire.context(c, params, Wire.readCross(wp, n, universe));
      } else {
        s = new Scheme(params.toString(), n, universe);
        c = Wire.context(s, Json.str(deployment, "id") + ":" + k);
        Wire.writeCross(wp, s);
        Wire.save(cp, c);
      }
      levels.add(s);
      contexts.add(c);
    }
    Map<String, Object> loaded;
    if (Files.exists(data.resolve("registry-state.json")))
      loaded = Wire.read(data.resolve("registry-state.json"));
    else {
      List<Object> latest = new ArrayList<>(), d1 = new ArrayList<>();
      for (Scheme s : levels) {
        latest.add(null);
        d1.add(new LinkedHashMap<String, Object>());
      }
      loaded =
          Json.obj(
              "ctr",
              0,
              "revision",
              0,
              "vehicles",
              new ArrayList<>(),
              "jobs",
              new ArrayList<>(),
              "D1",
              d1,
              "D2",
              new LinkedHashMap<String, Object>(),
              "latest",
              latest,
              "blocks",
              new LinkedHashMap<String, Object>());
      Wire.save(data.resolve("registry-state.json"), loaded);
    }
    if (!loaded.containsKey("attributeNames")) {
      Map<String, Object> names = new LinkedHashMap<>();
      // Legacy registrations used these labels in the UI. Reserve only used slots.
      String[] legacy = {"注册车辆", "授权通行", "应急服务", "市政服务", "车队成员", "城区许可", "设备可信", "交通调度", "物流运输", "路况采集"};
      for (String collection : Arrays.asList("vehicles", "jobs"))
        for (Object item : Json.list(loaded.get(collection)))
          for (int a : Wire.ints(Json.map(item).get("attributes"), universe))
            names.put("A" + (a + 1), a < legacy.length ? legacy[a] : "历史属性 A" + (a + 1));
      loaded.put("attributeNames", names);
      Wire.save(data.resolve("registry-state.json"), loaded);
    }
    synchronized (this) {
      db = loaded;
      context =
          Json.obj(
              "id",
              deployment.get("id"),
              "capacity",
              capacity,
              "universe",
              universe,
              "levels",
              contexts);
      phase = "就绪 · 等待 D 端注册车辆";
    }
  }

  String authenticate(String token) {
    String role = tokens.get(token);
    if (role == null) throw new SecurityException("节点令牌错误");
    return role;
  }

  void ready() {
    if (context == null) throw new IllegalStateException(phase + " " + error);
  }

  void commit(Map<String, Object> next) throws Exception {
    Wire.save(data.resolve("registry-state.json"), next);
    db = next;
  }

  synchronized Map<String, Object> view() {
    List<Object> vs = new ArrayList<>();
    if (db != null)
      for (Object v : Json.list(db.get("vehicles"))) {
        Map<String, Object> x = new LinkedHashMap<>(Json.map(v));
        x.remove("public");
        vs.add(x);
      }
    return Json.obj(
        "vehicles",
        vs,
        "ctr",
        db == null ? 0 : db.get("ctr"),
        "revision",
        db == null ? 0 : db.get("revision"),
        "latest",
        db == null ? Collections.emptyList() : new ArrayList<>(Json.list(db.get("latest"))),
        "capacity",
        capacity,
        "attributeNames",
        db == null ? Collections.emptyMap() : db.get("attributeNames"),
        "universe",
        universe,
        "deployment",
        context == null ? null : context.get("id"),
        "ready",
        context != null,
        "phase",
        phase,
        "error",
        error,
        "enrolled",
        vs.stream().filter(v -> Boolean.TRUE.equals(Json.map(v).get("registered"))).count());
  }

  public synchronized Map<String, Object> local(String path, Map<String, Object> b)
      throws Exception {
    ready();
    if (path.equals("/register")) {
      String role = Json.str(b, "role"), name = Json.str(b, "name").trim();
      if (!Arrays.asList("owner", "user").contains(role) || name.isEmpty() || name.length() > 60)
        throw new IllegalArgumentException("车辆名称或角色错误");
      Map<String, Object> next = Compiler6.copy(db);
      Set<Integer> attrs = registrationAttributes(next, b);
      List<Object> jobs = Json.list(next.get("jobs"));
      long pending = jobs.stream().filter(j -> !"done".equals(Json.map(j).get("status"))).count();
      if (Json.num(next, "ctr") + pending >= capacity)
        throw new IllegalStateException("注册容量已用完；注销不会回收身份编号");
      String id = UUID.randomUUID().toString();
      jobs.add(
          Json.obj(
              "id",
              id,
              "role",
              role,
              "name",
              name,
              "attributes",
              new ArrayList<>(attrs),
              "status",
              "pending",
              "created",
              System.currentTimeMillis()));
      commit(next);
      return Json.obj(
          "ok",
          true,
          "job",
          id,
          "message",
          "已排队，等待 " + (role.equals("owner") ? "A" : "C") + " 本地 KeyGen");
    }
    if (path.equals("/rejoin")) {
      int id = Json.num(b, "vehicle");
      Map<String, Object> next = Compiler6.copy(db), v = Compiler6.vehicle(next, id);
      if (Boolean.TRUE.equals(v.get("registered")))
        throw new IllegalStateException("车辆已在系统中");
      v.put("registered", true);
      v.put("rejoinedAt", System.currentTimeMillis());
      Map<String, Object> blocks = Json.map(next.get("blocks")), d2 = Json.map(next.get("D2"));
      List<Object> latest = Json.list(next.get("latest"));
      int revision = Json.num(next, "revision") + 1;
      Set<String> current = new LinkedHashSet<>();
      for (Object x : d2.values()) current.add((String) x);
      for (Object x : latest) if (x != null) current.add((String) x);
      for (String oldId : current) {
        Map<String, Object> old = Json.map(blocks.get(oldId));
        int k = Json.num(old, "level"), start = Json.num(old, "start");
        Scheme s = levels.get(k);
        if (id < start || id >= start + s.n) continue;
        // Rebuild from the immutable original registration, retaining all other removals.
        Map<String, Object> base = Json.map(blocks.get(k + "-" + start + "-base"));
        Scheme.Registry reg = Wire.registry(s, Json.map(base.get("registry")));
        for (int j = 0; j < s.n; j++)
          if (!Boolean.TRUE.equals(Compiler6.vehicle(next, start + j).get("registered")))
            reg = s.deregister(reg, j);
        String bid = k + "-" + start + "-r" + revision;
        blocks.put(bid, Json.obj("id", bid, "level", k, "start", start,
            "revision", revision, "registry", Wire.registry(reg)));
        for (Map.Entry<String, Object> e : d2.entrySet())
          if (oldId.equals(e.getValue())) e.setValue(bid);
        for (int j = 0; j < latest.size(); j++) if (oldId.equals(latest.get(j))) latest.set(j, bid);
      }
      next.put("revision", revision);
      commit(next);
      return Json.obj("ok", true, "vehicle", id, "message", "车辆已重新加入，保留原编号、属性和本机密钥");
    }
    if (path.equals("/deregister")) {
      int id = Json.num(b, "vehicle");
      Map<String, Object> next = Compiler6.copy(db), v = Compiler6.vehicle(next, id);
      Compiler6.active(v);
      v.put("registered", false);
      v.put("deregisteredAt", System.currentTimeMillis());
      Map<String, Object> blocks = Json.map(next.get("blocks")), d2 = Json.map(next.get("D2"));
      List<Object> latest = Json.list(next.get("latest"));
      int revision = Json.num(next, "revision") + 1;
      // Update every currently issued helper block containing this identity. Preserve immutable old
      // versions.
      Set<String> current = new LinkedHashSet<>();
      for (Object x : d2.values()) current.add((String) x);
      for (Object x : latest) if (x != null) current.add((String) x);
      for (String oldId : current) {
        Map<String, Object> old = Json.map(blocks.get(oldId));
        int k = Json.num(old, "level"), start = Json.num(old, "start");
        Scheme s = levels.get(k);
        if (id < start || id >= start + s.n) continue;
        Scheme.Registry r =
            s.deregister(Wire.registry(s, Json.map(old.get("registry"))), id - start);
        String bid = k + "-" + start + "-r" + revision;
        blocks.put(
            bid,
            Json.obj(
                "id",
                bid,
                "level",
                k,
                "start",
                start,
                "revision",
                revision,
                "registry",
                Wire.registry(r)));
        for (Map.Entry<String, Object> e : d2.entrySet())
          if (oldId.equals(e.getValue())) e.setValue(bid);
        for (int j = 0; j < latest.size(); j++) if (oldId.equals(latest.get(j))) latest.set(j, bid);
      }
      next.put("revision", revision);
      commit(next);
      return Json.obj(
          "ok",
          true,
          "vehicle",
          id,
          "revision",
          revision,
          "message",
          "Deregister 已更新聚合公钥和其他车辆的辅助项");
    }
    throw new SecurityException("D 端仅提供注册和注销");
  }

  Set<Integer> registrationAttributes(Map<String, Object> next, Map<String, Object> b) {
    Map<String, Object> names = Json.map(next.get("attributeNames"));
    Set<Integer> attrs = new LinkedHashSet<>();
    if (b.containsKey("attributeNames")) {
      for (Object value : Json.list(b.get("attributeNames"))) {
        if (!(value instanceof String)) throw new IllegalArgumentException("请输入属性名称");
        String name = ((String) value).trim();
        if (name.isEmpty() || name.length() > 60)
          throw new IllegalArgumentException("属性名称须为 1–60 个字符");
        String code = null;
        for (Map.Entry<String, Object> e : names.entrySet())
          if (name.equals(e.getValue())) { code = e.getKey(); break; }
        if (code == null) {
          for (int i = 1; i <= universe; i++)
            if (!names.containsKey("A" + i)) { code = "A" + i; break; }
          if (code == null) throw new IllegalStateException("属性字典已满，最多 " + universe + " 个不同属性");
          names.put(code, name);
        }
        attrs.add(Integer.parseInt(code.substring(1)) - 1);
      }
    } else {
      // Retain numeric API compatibility, reserving slots so names cannot silently change.
      attrs.addAll(Wire.ints(b.get("attributes"), universe));
      for (int a : attrs) names.putIfAbsent("A" + (a + 1), "A" + (a + 1));
    }
    if (attrs.isEmpty()) throw new IllegalArgumentException("至少一个属性");
    return attrs;
  }

  public synchronized Map<String, Object> api(String path, Map<String, Object> b, String role)
      throws Exception {
    if (path.equals("/view")) return view();
    ready();
    if (path.equals("/context")) return context;
    if (path.equals("/block")) {
      Object block = Json.map(db.get("blocks")).get(Json.str(b, "id"));
      if (block == null) throw new IllegalArgumentException("Unknown helper block");
      return Json.map(block);
    }
    if (path.equals("/public")) {
      Map<String, Object> v = Compiler6.vehicle(db, Json.num(b, "vehicle"));
      return Json.obj("public", v.get("public"));
    }
    if (path.equals("/update")) {
      int i = Json.num(b, "vehicle"), k = Json.num(b, "level");
      Map<String, Object> v = Compiler6.vehicle(db, i);
      if (!role.equals("cloud") && !role.equals(v.get("role")))
        throw new SecurityException("非本端车辆");
      Compiler6.active(v);
      return Json.obj("block", Json.map(db.get("D2")).get(i + ":" + k));
    }
    if (path.equals("/job")) {
      for (Object o : Json.list(db.get("jobs"))) {
        Map<String, Object> j = Json.map(o);
        if ("done".equals(j.get("status"))) continue;
        return role.equals(j.get("role"))
            ? Json.obj("job", j, "ctr", db.get("ctr"))
            : Json.obj("job", null);
      }
      return Json.obj("job", null);
    }
    if (path.equals("/submit")) return submit(b, role);
    throw new SecurityException("该节点无权执行注册/注销管理操作");
  }

  Map<String, Object> submit(Map<String, Object> b, String role) throws Exception {
    Map<String, Object> job = null;
    for (Object o : Json.list(db.get("jobs")))
      if (Json.str(b, "job").equals(Json.map(o).get("id"))) job = Json.map(o);
    if (job == null || !role.equals(job.get("role"))) throw new SecurityException("未获 D 端注册授权");
    if ("done".equals(job.get("status")))
      return Json.obj("ok", true, "vehicle", job.get("vehicle"));
    for (Object o : Json.list(db.get("jobs")))
      if (!"done".equals(Json.map(o).get("status"))) {
        if (!job.get("id").equals(Json.map(o).get("id")))
          throw new IllegalStateException("请按注册队列顺序提交");
        break;
      }
    int ctr = Json.num(db, "ctr");
    if (Json.num(b, "ctr") != ctr || ctr >= capacity)
      throw new IllegalStateException("STALE_COUNTER");
    List<Object> pubs = Json.list(b.get("public"));
    if (pubs.size() != levels.size()) throw new IllegalArgumentException("层数错误");
    long startTime = System.nanoTime();
    List<Object> clean = new ArrayList<>();
    for (int k = 0; k < levels.size(); k++) {
      Scheme s = levels.get(k);
      Map<String, Object> pub = Json.map(pubs.get(k));
      if (pub.containsKey("r") || pub.containsKey("q") || pub.containsKey("z"))
        throw new IllegalArgumentException("禁止上传私钥");
      Scheme.Keys key = Wire.key(s, pub, false);
      if (!s.isValid(ctr % s.n, key)) throw new IllegalArgumentException("INVALID_PUBLIC_KEY");
      clean.add(Wire.key(key, false));
    }
    Map<String, Object> next = Compiler6.copy(db);
    List<Object> vs = Json.list(next.get("vehicles"));
    vs.add(
        Json.obj(
            "id",
            ctr,
            "number",
            String.format("V-%03d", ctr + 1),
            "name",
            job.get("name"),
            "role",
            role,
            "attributes",
            job.get("attributes"),
            "registered",
            true,
            "public",
            clean,
            "registeredAt",
            System.currentTimeMillis(),
            "keygenMs",
            b.get("keygenMs")));
    List<Object> d1 = Json.list(next.get("D1")), latest = Json.list(next.get("latest"));
    Map<String, Object> d2 = Json.map(next.get("D2")), blocks = Json.map(next.get("blocks"));
    for (int k = 0; k < levels.size(); k++) {
      Scheme s = levels.get(k);
      int slot = ctr % s.n;
      Json.map(d1.get(k))
          .put(
              "" + slot,
              Json.obj(
                  "vehicle", ctr, "public", clean.get(k), "attributes", job.get("attributes")));
      if (slot != s.n - 1) continue;
      int first = ctr - s.n + 1;
      Scheme.Keys[] keys = new Scheme.Keys[s.n];
      List<Set<Integer>> sets = new ArrayList<>();
      for (int j = 0; j < s.n; j++) {
        Map<String, Object> v = Json.map(vs.get(first + j));
        keys[j] = Wire.key(s, Json.map(Json.list(v.get("public")).get(k)), false);
        sets.add(Wire.ints(v.get("attributes"), universe));
      }
      Scheme.Registry reg = s.register(keys, sets);
      String base = k + "-" + first + "-base";
      blocks.put(
          base,
          Json.obj(
              "id",
              base,
              "level",
              k,
              "start",
              first,
              "revision",
              -1,
              "registry",
              Wire.registry(reg)));
      // A later full block must not restore a previously deregistered member.
      for (int j = 0; j < s.n; j++)
        if (!Boolean.TRUE.equals(Json.map(vs.get(first + j)).get("registered")))
          reg = s.deregister(reg, j);
      String bid = k + "-" + first + "-r" + (Json.num(next, "revision") + 1);
      blocks.put(
          bid,
          Json.obj(
              "id",
              bid,
              "level",
              k,
              "start",
              first,
              "revision",
              Json.num(next, "revision") + 1,
              "registry",
              Wire.registry(reg)));
      latest.set(k, bid);
      for (int j = 0; j < s.n; j++) d2.put((first + j) + ":" + k, bid);
    }
    double ms = (System.nanoTime() - startTime) / 1e6;
    Json.map(vs.get(ctr)).put("registrationMs", ms);
    for (Object o : Json.list(next.get("jobs")))
      if (job.get("id").equals(Json.map(o).get("id"))) {
        Json.map(o).put("status", "done");
        Json.map(o).put("vehicle", ctr);
      }
    next.put("ctr", ctr + 1);
    next.put("revision", Json.num(next, "revision") + 1);
    commit(next);
    return Json.obj("ok", true, "vehicle", ctr, "registrationMs", ms);
  }

  synchronized Map<String, Object> localState() {
    Map<String, Object> s = view();
    s.put("jobs", db == null ? Collections.emptyList() : db.get("jobs"));
    s.put("localReady", context != null);
    s.put("localPhase", phase);
    return s;
  }
}
