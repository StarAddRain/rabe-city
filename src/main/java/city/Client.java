package city;

import it.unisa.dia.gas.jpbc.Element;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import rabe.*;

/** A/C agents: local multi-level private keys; vehicle-scoped plaintext mailbox. */
public final class Client {
  final String role, url, token;
  final Path data;
  final Peer peer;
  volatile String phase = "等待 D 公共参数", error = "";
  volatile boolean ready = false;
  Map<String, Object> saved;
  final Map<Integer, List<Scheme.Keys>> keys = new HashMap<>();
  final Map<Integer, List<Object>> inbox = new HashMap<>();

  Client(Properties p, Path params) throws Exception {
    role = p.getProperty("role");
    url = p.getProperty("cloud");
    token = p.getProperty("token");
    data = Paths.get(p.getProperty("data"));
    Files.createDirectories(data);
    peer = new Peer(p.getProperty("curator"), token, params);
    Thread t =
        new Thread(
            () -> {
              while (true) {
                try {
                  work();
                  error = "";
                } catch (Exception e) {
                  error = e.getMessage();
                  phase = ready ? "等待注册任务 / D 连接恢复" : "等待 D 公共参数";
                }
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "local-key-agent");
    t.setDaemon(true);
    t.start();
  }

  Map<String, Object> remote(String path, Map<String, Object> b) throws Exception {
    return request(url + "/node" + path, token, b);
  }

  static Map<String, Object> request(String target, String token, Map<String, Object> b)
      throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
    c.setConnectTimeout(3000);
    c.setReadTimeout(120000);
    c.setRequestMethod("POST");
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    c.setRequestProperty("Authorization", "Bearer " + token);
    c.setDoOutput(true);
    byte[] bytes = Json.write(b).getBytes(StandardCharsets.UTF_8);
    c.setFixedLengthStreamingMode(bytes.length);
    try {
      try (OutputStream out = c.getOutputStream()) {
        out.write(bytes);
      }
      int code = c.getResponseCode();
      Map<String, Object> result =
          Json.map(
              Json.read(
                  new String(
                      Main.readLimited(
                          code < 400 ? c.getInputStream() : c.getErrorStream(), 64 * 1024 * 1024),
                      StandardCharsets.UTF_8)));
      if (code >= 400)
        throw new IOException(result.getOrDefault("error", "HTTP " + code).toString());
      return result;
    } finally {
      c.disconnect();
    }
  }

  synchronized void work() throws Exception {
    if (!ready) {
      peer.init();
      Path file = data.resolve("private-keys.json");
      saved =
          Files.exists(file)
              ? Wire.read(file)
              : Json.obj(
                  "deployment",
                  peer.context.get("id"),
                  "keys",
                  new LinkedHashMap<String, Object>());
      if (!peer.context.get("id").equals(saved.get("deployment")))
        throw new IllegalStateException("D 部署已改变，请使用新的 data 目录，保留原私钥");
      for (Map.Entry<String, Object> e : Json.map(saved.get("keys")).entrySet())
        keys.put(
            Integer.parseInt(e.getKey()), decode(Json.list(Json.map(e.getValue()).get("secret"))));
      if (Files.exists(data.resolve("inbox.json")))
        for (Map.Entry<String, Object> e : Wire.read(data.resolve("inbox.json")).entrySet())
          inbox.put(Integer.parseInt(e.getKey()), Json.list(e.getValue()));
      ready = true;
    }
    Map<String, Object> task = peer.call("/job", Json.obj());
    if (task.get("job") == null) {
      phase = "就绪 · 等待 D 注册任务";
      return;
    }
    Map<String, Object> job = Json.map(task.get("job"));
    int ctr = Json.num(task, "ctr");
    String jid = Json.str(job, "id");
    phase = "本机 KeyGen → D RegPK：" + job.get("name");
    Map<String, Object> records = Json.map(saved.get("keys"));
    Map<String, Object> record =
        records.containsKey("" + ctr) ? Json.map(records.get("" + ctr)) : null;
    if (record != null && !jid.equals(record.get("job")))
      throw new IllegalStateException("身份编号已有其他本地密钥，拒绝覆盖");
    if (record == null) {
      long start = System.nanoTime();
      List<Scheme.Keys> list = new ArrayList<>();
      List<Object> secret = new ArrayList<>();
      for (Scheme s : peer.levels) {
        Scheme.Keys key = s.keyGen(ctr % s.n);
        list.add(key);
        secret.add(Wire.key(key, true));
      }
      record =
          Json.obj("job", jid, "secret", secret, "keygenMs", (System.nanoTime() - start) / 1e6);
      records.put("" + ctr, record);
      Wire.save(data.resolve("private-keys.json"), saved);
      keys.put(ctr, list);
    }
    List<Object> pubs = new ArrayList<>();
    for (Scheme.Keys key : keys.get(ctr)) pubs.add(Wire.key(key, false));
    peer.call(
        "/submit",
        Json.obj("job", jid, "ctr", ctr, "public", pubs, "keygenMs", record.get("keygenMs")));
    phase = "就绪";
  }

  List<Scheme.Keys> decode(List<Object> dto) {
    List<Scheme.Keys> out = new ArrayList<>();
    for (int k = 0; k < dto.size(); k++)
      out.add(Wire.key(peer.levels.get(k), Json.map(dto.get(k)), true));
    return out;
  }

  List<Scheme.Keys> local(int id) {
    List<Scheme.Keys> k = keys.get(id);
    if (k == null) throw new SecurityException("所选车辆私钥不在本机");
    return k;
  }

  Map<String, Object> state() {
    Map<String, Object> s;
    try {
      s = remote("/state", Json.obj());
    } catch (Exception e) {
      s = Main.emptyState();
      s.put("connectionError", e.getMessage());
    }
    s.put("localReady", ready);
    s.put("localPhase", phase);
    s.put("localError", error);
    return s;
  }

  synchronized Map<String, Object> inbox(int vehicle) throws Exception {
    if (!role.equals("user")) throw new SecurityException("明文仅属于 C 端数据用户");
    local(vehicle);
    Map<String, Object> v = peer.vehicle(vehicle);
    if (!role.equals(v.get("role"))) throw new SecurityException("非本端车辆");
    return Json.obj(
        "vehicle",
        vehicle,
        "inbox",
        new ArrayList<>(inbox.getOrDefault(vehicle, Collections.emptyList())));
  }

  public synchronized Object api(String path, Map<String, Object> b) throws Exception {
    if (path.equals("/register") || path.equals("/deregister"))
      throw new SecurityException("仅 D 端可注册/注销");
    if (!ready) throw new IllegalStateException(phase);
    Map<String, Object> view = peer.view();
    if (path.equals("/send")) {
      if (!role.equals("owner")) throw new SecurityException("仅 A 可发送");
      int sender = Json.num(b, "sender");
      List<Scheme.Keys> sk = local(sender);
      Map<String, Object> v = Compiler6.vehicle(view, sender);
      Compiler6.active(v);
      String text = Json.str(b, "text"), expression = Json.str(b, "policy");
      if (text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 16384)
        throw new IllegalArgumentException("文字为空或超过 16KB");
      int ctr = Json.num(view, "ctr"), authLevel = Compiler6.level(ctr, sender);
      Scheme auth = peer.levels.get(authLevel);
      Policy policy = Policies.parse(expression, auth.u);
      long start = System.nanoTime();
      Element m = auth.message();
      byte[] nonce = new byte[12];
      new SecureRandom().nextBytes(nonce);
      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.ENCRYPT_MODE, Hybrid.key(m), new GCMParameterSpec(128, nonce));
      byte[] payload = aes.doFinal(text.getBytes(StandardCharsets.UTF_8));
      List<Object> parts = new ArrayList<>(), latest = Json.list(view.get("latest"));
      for (int k = 0; k < peer.levels.size(); k++) {
        if (latest.get(k) == null) {
          parts.add(null);
          continue;
        }
        Scheme s = peer.levels.get(k);
        String bid = (String) latest.get(k);
        Scheme.Ciphertext c =
            s.encryptForward(peer.registry(bid), policy, Wire.dec(s.pairing, Wire.enc(m), true));
        c.sender = sender % s.n;
        parts.add(Json.obj("block", bid, "cipher", Compiler6.forward(c)));
      }
      String authBlock = (String) latest.get(authLevel);
      Map<String, Object> envelope =
          Json.obj(
              "ctr",
              ctr,
              "revision",
              view.get("revision"),
              "sender",
              sender,
              "expression",
              expression,
              "parts",
              parts,
              "authBlock",
              authBlock,
              "nonce",
              Base64.getEncoder().encodeToString(nonce),
              "payload",
              Base64.getEncoder().encodeToString(payload),
              "title",
              b.getOrDefault("title", "城市车辆数据"));
      Scheme.Ciphertext proof =
          auth.senderProof(
              peer.registry(authBlock),
              sender % auth.n,
              Wire.ints(v.get("attributes"), auth.u),
              sk.get(authLevel).q,
              Compiler6.challenge(envelope, auth.p));
      envelope.put("proof", Compiler6.proof(proof));
      envelope.put("encryptMs", (System.nanoTime() - start) / 1e6);
      return remote("/publish", envelope);
    }
    if (path.equals("/decrypt")) {
      if (!role.equals("user")) throw new SecurityException("仅 C 本地解密");
      int receiver = Json.num(b, "receiver");
      List<Scheme.Keys> sk = local(receiver);
      Compiler6.active(Compiler6.vehicle(view, receiver));
      Map<String, Object> t = remote("/transform", b);
      if (!Boolean.TRUE.equals(t.get("ok"))) return t;
      int k = Json.num(t, "level");
      Scheme s = peer.levels.get(k);
      // Section 6 GetUpdate: acquire the helper associated with this ciphertext's immutable block.
      Map<String, Object> update = peer.call("/update", Json.obj("vehicle", receiver, "level", k));
      if (update.get("block") == null) throw new IllegalStateException("GET_UPDATE_REQUIRED");
      Map<String, Object> helper = peer.block(Json.str(t, "block"));
      int first = Json.num(helper, "start");
      if (receiver < first || receiver >= first + s.n)
        throw new SecurityException("HELPER_IDENTITY_MISMATCH");
      Scheme.Transformed transformed =
          new Scheme.Transformed(
              Wire.dec(s.pairing, t.get("D1"), true), Wire.dec(s.pairing, t.get("D4"), true));
      long start = System.nanoTime();
      byte[] plain =
          Hybrid.decryptTransformed(
              s,
              sk.get(k).r,
              transformed,
              Base64.getDecoder().decode(Json.str(t, "nonce")),
              Base64.getDecoder().decode(Json.str(t, "payload")));
      double ms = (System.nanoTime() - start) / 1e6;
      if (plain == null)
        return Json.obj(
            "ok",
            false,
            "reason",
            Boolean.TRUE.equals(t.get("revoked")) ? "REVOKED" : "INTEGRITY",
            "receiver",
            receiver,
            "decryptMs",
            ms,
            "transformMs",
            t.get("transformMs"));
      // Recheck active status before releasing plaintext if deregistration raced with Transform.
      Compiler6.active(peer.vehicle(receiver));
      Map<String, Object> out =
          Json.obj(
              "ok",
              true,
              "id",
              b.get("id"),
              "receiver",
              receiver,
              "text",
              new String(plain, StandardCharsets.UTF_8),
              "version",
              t.get("version"),
              "decryptMs",
              ms,
              "transformMs",
              t.get("transformMs"),
              "at",
              System.currentTimeMillis());
      List<Object> list = inbox.computeIfAbsent(receiver, x -> new ArrayList<>());
      list.add(0, out);
      if (list.size() > 30) list.remove(list.size() - 1);
      Map<String, Object> box = new LinkedHashMap<>();
      for (Map.Entry<Integer, List<Object>> e : inbox.entrySet())
        box.put(e.getKey().toString(), e.getValue());
      Wire.save(data.resolve("inbox.json"), box);
      return out;
    }
    if (path.equals("/revoke")) {
      if (!role.equals("owner")) throw new SecurityException("仅 A 可撤销指定密文");
      Map<String, Object> m = remote("/message", b);
      int sender = Json.num(m, "sender"),
          target = Json.num(b, "target"),
          k = Compiler6.level(Json.num(m, "ctr"), target);
      Compiler6.active(Compiler6.vehicle(view, sender));
      Scheme s = peer.levels.get(k);
      Map<String, Object> part = Json.map(Json.list(m.get("parts")).get(k));
      Scheme.Ciphertext c = Compiler6.forward(s, Json.map(part.get("cipher")));
      long start = System.nanoTime();
      Scheme.UpdateKey uk = s.updateKeyGen(c, target % s.n, local(sender).get(k));
      Map<String, Object> r =
          remote(
              "/update",
              Json.obj(
                  "id",
                  m.get("id"),
                  "version",
                  m.get("version"),
                  "target",
                  target,
                  "level",
                  k,
                  "update",
                  Wire.update(uk)));
      r.put("totalMs", (System.nanoTime() - start) / 1e6);
      return r;
    }
    if (path.equals("/leak")) {
      if (!role.equals("user")) throw new SecurityException("仅 C 可复制用户私钥");
      int id = Json.num(b, "source");
      return remote("/leak", Json.obj("source", id, "scalar", local(id).get(0).r.toString()));
    }
    if (path.equals("/trace") || path.equals("/motion/set")) return remote(path, b);
    throw new SecurityException("无此本地操作");
  }
}
