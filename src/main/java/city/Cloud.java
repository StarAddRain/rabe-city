package city;

import it.unisa.dia.gas.jpbc.Element;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import rabe.*;

/** B: storage and transformation. No registration authority or ordinary private keys. */
public final class Cloud {
  final Path data;
  final Peer peer;
  final Motion motion;
  final Map<String, String> tokens = new HashMap<>();
  final Map<String, Map<String, Object>> messages = new LinkedHashMap<>(),
      samples = new LinkedHashMap<>();
  final List<Object> events = new ArrayList<>();
  final Map<String, Long> seen = new HashMap<>();
  volatile String phase = "等待 D 公共参数", error = "";
  volatile boolean ready = false;

  Cloud(Properties p, Path params) throws Exception {
    data = Paths.get(p.getProperty("data"));
    Files.createDirectories(data);
    motion = new Motion(data);
    for (String r : Arrays.asList("owner", "user", "curator"))
      tokens.put(p.getProperty(r + "Token"), r);
    peer = new Peer(p.getProperty("curator"), p.getProperty("token"), params);
    Thread t =
        new Thread(
            () -> {
              while (!ready) {
                try {
                  peer.init();
                  synchronized (this) {
                    Path marker = data.resolve("deployment.json");
                    if (Files.exists(marker)
                        && !peer.context.get("id").equals(Wire.read(marker).get("id")))
                      throw new IllegalStateException("D 部署改变，请使用新的 B data 目录");
                    Wire.save(marker, Json.obj("id", peer.context.get("id")));
                    load("messages.json", messages);
                    load("samples.json", samples);
                    ready = true;
                    phase = "就绪";
                  }
                } catch (Exception e) {
                  error = e.getMessage();
                  try {
                    Thread.sleep(1500);
                  } catch (InterruptedException x) {
                    return;
                  }
                }
              }
            },
            "cloud-context");
    t.setDaemon(true);
    t.start();
  }

  void load(String file, Map<String, Map<String, Object>> target) throws Exception {
    if (Files.exists(data.resolve(file)))
      for (Map.Entry<String, Object> e : Wire.read(data.resolve(file)).entrySet())
        target.put(e.getKey(), Json.map(e.getValue()));
  }

  String authenticate(String token) {
    String role = tokens.get(token);
    if (role == null) throw new SecurityException("节点令牌错误");
    return role;
  }

  void require(String actual, String expected) {
    if (!expected.equals(actual)) throw new SecurityException("此操作需要 " + expected + " 节点");
  }

  void event(String type, String text, Integer vehicle) {
    events.add(
        Json.obj(
            "id",
            UUID.randomUUID().toString(),
            "at",
            System.currentTimeMillis(),
            "type",
            type,
            "text",
            text,
            "vehicle",
            vehicle));
    if (events.size() > 80) events.remove(0);
  }

  public synchronized Map<String, Object> state() {
    Map<String, Object> out;
    try {
      out = peer.view();
    } catch (Exception e) {
      out = Main.emptyState();
      out.put("connectionError", e.getMessage());
    }
    List<Object> ms = new ArrayList<>(), ss = new ArrayList<>();
    Set<Integer> leaked = new HashSet<>();
    for (Map<String, Object> m : messages.values()) ms.add(meta(m));
    for (Map<String, Object> s : samples.values()) {
      leaked.add(Json.num(s, "source"));
      ss.add(
          Json.obj(
              "id",
              s.get("id"),
              "source",
              s.get("source"),
              "created",
              s.get("created"),
              "traced",
              s.get("traced")));
    }
    for (Object o : Json.list(out.get("vehicles"))) {
      Map<String, Object> v = Json.map(o);
      v.put("leaked", leaked.contains(Json.num(v, "id")));
    }
    long now = System.currentTimeMillis();
    out.put("messages", ms);
    out.put("samples", ss);
    out.put("events", new ArrayList<>(events));
    out.put(
        "nodes",
        Json.obj(
            "cloud",
            true,
            "curator",
            !out.containsKey("connectionError"),
            "owner",
            now - seen.getOrDefault("owner", 0L) < 10000,
            "user",
            now - seen.getOrDefault("user", 0L) < 10000));
    out.put("localReady", ready);
    out.put("localPhase", phase);
    return out;
  }

  Map<String, Object> meta(Map<String, Object> m) {
    Map<String, Object> ct = null;
    for (Object o : Json.list(m.get("parts")))
      if (o != null) {
        ct = Json.map(Json.map(o).get("cipher"));
        break;
      }
    return Json.obj(
        "id",
        m.get("id"),
        "sender",
        m.get("sender"),
        "title",
        m.get("title"),
        "policy",
        m.get("expression"),
        "version",
        m.get("version"),
        "created",
        m.get("created"),
        "revoked",
        m.get("revoked"),
        "bytes",
        Base64.getDecoder().decode(Json.str(m, "payload")).length,
        "preview",
        Json.str(ct, "C1").substring(0, 48),
        "encryptMs",
        m.get("encryptMs"),
        "ctr",
        m.get("ctr"));
  }

  Map<String, Object> message(Map<String, Object> b) {
    Map<String, Object> m = messages.get(Json.str(b, "id"));
    if (m == null) throw new IllegalArgumentException("密文不存在");
    return m;
  }

  public Object api(String path, Map<String, Object> b, String role) throws Exception {
    // Simulation clock is independent of long pairing computations.
    if (path.equals("/motion")) return motion.snapshot();
    if (path.equals("/motion/set")) {
      if (role.equals("curator")) throw new SecurityException("D 只有注册/注销权限");
      return motion.set(Boolean.TRUE.equals(b.get("paused")));
    }
    synchronized (this) {
      return locked(path, b, role);
    }
  }

  Object locked(String path, Map<String, Object> b, String role) throws Exception {
    seen.put(role, System.currentTimeMillis());
    if (path.equals("/state")) return state();
    if (role.equals("curator")) throw new SecurityException("D 不提供云端数据操作");
    if (!ready) throw new IllegalStateException(phase);
    if (path.equals("/register") || path.equals("/deregister") || path.equals("/enroll"))
      throw new SecurityException("仅 D 可管理车辆注册/注销");
    Map<String, Object> view = peer.view();
    if (path.equals("/publish")) {
      require(role, "owner");
      int sender = Json.num(b, "sender"), ctr = Json.num(b, "ctr");
      Map<String, Object> v = Compiler6.vehicle(view, sender);
      require(Json.str(v, "role"), "owner");
      Compiler6.active(v);
      if (ctr != Json.num(view, "ctr") || Json.num(b, "revision") != Json.num(view, "revision"))
        throw new IllegalStateException("注册状态已更新，请重新发送");
      List<Object> parts = Json.list(b.get("parts")), latest = Json.list(view.get("latest"));
      if (parts.size() != latest.size()) throw new IllegalArgumentException("密文层数错误");
      Policy policy = Policies.parse(Json.str(b, "expression"), Json.num(view, "universe"));
      for (int k = 0; k < parts.size(); k++) {
        if (latest.get(k) == null) {
          if (parts.get(k) != null) throw new IllegalArgumentException("未启用层");
          continue;
        }
        Map<String, Object> part = Json.map(parts.get(k));
        if (!latest.get(k).equals(part.get("block"))) throw new IllegalStateException("辅助项版本已变化");
        Scheme s = peer.levels.get(k);
        Scheme.Ciphertext c = Compiler6.forward(s, Json.map(part.get("cipher")));
        if (c.sender != sender % s.n
            || !Json.write(Wire.policy(policy)).equals(Json.write(Wire.policy(c.policy))))
          throw new IllegalArgumentException("密文策略或发送槽位不匹配");
      }
      int k = Compiler6.level(ctr, sender);
      Scheme s = peer.levels.get(k);
      if (!latest.get(k).equals(b.get("authBlock"))) throw new IllegalArgumentException("发送验证块错误");
      Scheme.Ciphertext proof = Compiler6.proof(s, Json.map(b.get("proof")));
      if (proof.sender != sender % s.n
          || !proof.X2.isEqual(s.A[proof.sender])
          || !Wire.ints(v.get("attributes"), s.u).containsAll(proof.claimed))
        throw new IllegalArgumentException("发送属性或身份不匹配");
      if (Base64.getDecoder().decode(Json.str(b, "nonce")).length != 12
          || Base64.getDecoder().decode(Json.str(b, "payload")).length > 65536
          || Json.str(b, "title").length() > 80) throw new IllegalArgumentException("数据长度错误");
      Map<String, Object> m = Compiler6.copy(b);
      List<Object> ris = new ArrayList<>();
      for (int j = 0; j < parts.size(); j++)
        ris.add(parts.get(j) == null ? null : Wire.ris(peer.levels.get(j).initialRI()));
      String id = UUID.randomUUID().toString().substring(0, 8);
      m.put("id", id);
      m.put("ri", ris);
      m.put("version", 0);
      m.put("revoked", new ArrayList<>());
      m.put("created", System.currentTimeMillis());
      messages.put(id, m);
      save();
      event("encrypted", "密文 " + id + " 已上传（ctr=" + ctr + "）", sender);
      return meta(m);
    }
    if (path.equals("/message")) {
      Map<String, Object> m = Compiler6.copy(message(b));
      m.remove("ri");
      return m;
    }
    if (path.equals("/transform")) {
      require(role, "user");
      int receiver = Json.num(b, "receiver");
      Map<String, Object> v = Compiler6.vehicle(view, receiver);
      require(Json.str(v, "role"), "user");
      Compiler6.active(v);
      Map<String, Object> m = message(b);
      Compiler6.active(Compiler6.vehicle(view, Json.num(m, "sender")));
      if (receiver >= Json.num(m, "ctr"))
        return Json.obj("ok", false, "reason", "NOT_REGISTERED_AT_ENCRYPTION");
      int k = Compiler6.level(Json.num(m, "ctr"), receiver);
      Scheme s = peer.levels.get(k);
      Map<String, Object> part = Json.map(Json.list(m.get("parts")).get(k));
      String bid = Json.str(part, "block");
      Scheme.Registry r = peer.registry(bid);
      Scheme.Ciphertext c = Compiler6.forward(s, Json.map(part.get("cipher")));
      int ak = Json.num(peer.block(Json.str(m, "authBlock")), "level");
      Scheme auth = peer.levels.get(ak);
      Scheme.Ciphertext proof = Compiler6.proof(auth, Json.map(m.get("proof")));
      Policy reverse = Policies.parse(Json.str(b, "reverse"), s.u);
      String reason = null;
      long start = System.nanoTime();
      if (c.policy.reconstruct(r.attributes.get(receiver % s.n), s.p) == null)
        reason = "RECEIVER_POLICY";
      else if (reverse.reconstruct(proof.claimed, auth.p) == null) reason = "SENDER_POLICY";
      else if (!auth.verifyProof(
          peer.registry(Json.str(m, "authBlock")), proof, reverse, Compiler6.challenge(m, auth.p)))
        reason = "SENDER_VERIFICATION";
      if (reason != null)
        return Json.obj(
            "ok", false, "reason", reason, "transformMs", (System.nanoTime() - start) / 1e6);
      Element ri = Wire.ris(s, Json.list(m.get("ri")).get(k))[receiver % s.n];
      Scheme.Transformed t = s.transformForward(r, receiver % s.n, ri, c);
      event(
          "transformed",
          "密文 " + m.get("id") + " → V-" + String.format("%03d", receiver + 1),
          receiver);
      return Json.obj(
          "ok",
          true,
          "level",
          k,
          "block",
          bid,
          "D1",
          Wire.enc(t.D1),
          "D4",
          Wire.enc(t.D4),
          "nonce",
          m.get("nonce"),
          "payload",
          m.get("payload"),
          "version",
          m.get("version"),
          "revoked",
          Wire.ints(m.get("revoked"), Json.num(view, "capacity")).contains(receiver),
          "transformMs",
          (System.nanoTime() - start) / 1e6);
    }
    if (path.equals("/update")) {
      require(role, "owner");
      Map<String, Object> m = message(b);
      int sender = Json.num(m, "sender"),
          target = Json.num(b, "target"),
          k = Compiler6.level(Json.num(m, "ctr"), target);
      Compiler6.active(Compiler6.vehicle(view, sender));
      Compiler6.vehicle(view, target);
      if (Json.num(b, "level") != k || Json.num(b, "version") != Json.num(m, "version"))
        throw new IllegalStateException("密文已更新，请重试");
      Set<Integer> revoked = Wire.ints(m.get("revoked"), Json.num(view, "capacity"));
      if (revoked.contains(target)) throw new IllegalStateException("该车辆已被此密文撤销");
      Scheme s = peer.levels.get(k);
      Scheme.UpdateKey uk = Wire.update(s, Json.map(b.get("update")));
      if (uk.owner != sender % s.n || uk.target != target % s.n)
        throw new IllegalArgumentException("撤销槽位不匹配");
      Map<String, Object> next = Compiler6.copy(m),
          part = Json.map(Json.list(next.get("parts")).get(k));
      Scheme.Registry reg = peer.registry(Json.str(part, "block"));
      // The sender may be outside this receiving block. Bind UK to its own registered public Z.
      List<Object> pub = Json.list(peer.call("/public", Json.obj("vehicle", sender)).get("public"));
      reg.Zkeys[uk.owner] = Wire.key(s, Json.map(pub.get(k)), false).Z;
      long start = System.nanoTime();
      Scheme.Ciphertext c =
          s.updateVCS(reg, Compiler6.forward(s, Json.map(part.get("cipher"))), uk);
      Element[] ri = s.updateKC(reg, Wire.ris(s, Json.list(next.get("ri")).get(k)), uk);
      part.put("cipher", Compiler6.forward(c));
      Json.list(next.get("ri")).set(k, Wire.ris(ri));
      next.put("version", Json.num(m, "version") + 1);
      revoked.add(target);
      next.put("revoked", new ArrayList<>(revoked));
      messages.put(Json.str(m, "id"), next);
      save();
      event("revoked", "指定密文已撤销 V-" + String.format("%03d", target + 1), target);
      return Json.obj(
          "ok",
          true,
          "version",
          next.get("version"),
          "updateMs",
          (System.nanoTime() - start) / 1e6);
    }
    if (path.equals("/leak")) {
      require(role, "user");
      int source = Json.num(b, "source");
      Map<String, Object> v = Compiler6.vehicle(view, source);
      require(Json.str(v, "role"), "user");
      BigInteger scalar = new BigInteger(Json.str(b, "scalar"));
      Scheme s = peer.levels.get(0);
      Scheme.Registry original = peer.registry("0-" + source + "-base");
      if (scalar.signum() <= 0
          || scalar.compareTo(s.p) >= 0
          || !s.pow(s.g, scalar).isEqual(original.Tkeys[0]))
        throw new IllegalArgumentException("无效泄露密钥");
      String id = UUID.randomUUID().toString().substring(0, 8);
      samples.put(
          id,
          Json.obj(
              "id",
              id,
              "source",
              source,
              "scalar",
              scalar.toString(),
              "created",
              System.currentTimeMillis(),
              "traced",
              null));
      Wire.save(data.resolve("samples.json"), samples);
      event("leaked", "已获取真实密钥复制样本 " + id, source);
      return Json.obj("ok", true, "id", id);
    }
    if (path.equals("/trace")) {
      Map<String, Object> sample = samples.get(Json.str(b, "sample"));
      if (sample == null) throw new IllegalArgumentException("无此样本");
      BigInteger scalar = new BigInteger(Json.str(sample, "scalar"));
      Scheme s = peer.levels.get(0);
      long start = System.nanoTime();
      int found = -1;
      // Search public registrations and run original Trace equations; never trust sample.source.
      for (Object o : Json.list(view.get("vehicles"))) {
        int i = Json.num(Json.map(o), "id");
        if (s.traceLeakedScalar(peer.registry("0-" + i + "-base"), scalar) == 0) {
          if (found >= 0) throw new SecurityException("身份匹配不唯一");
          found = i;
        }
      }
      if (found < 0) throw new SecurityException("Trace 验证失败");
      sample.put("traced", found);
      Wire.save(data.resolve("samples.json"), samples);
      event("traced", "Trace 识别身份 i′=" + (found + 1), found);
      return Json.obj(
          "ok",
          true,
          "slot",
          found,
          "identity",
          found + 1,
          "traceMs",
          (System.nanoTime() - start) / 1e6,
          "checks",
          Arrays.asList("泄露标量匹配独立注册公钥", "原 Trace 聚合公钥方程", "O/K 配对一致性", "E/槽位绑定与全局身份映射"));
    }
    throw new SecurityException("未知或未授权的云端操作");
  }

  void save() throws Exception {
    Wire.save(data.resolve("messages.json"), messages);
  }
}
