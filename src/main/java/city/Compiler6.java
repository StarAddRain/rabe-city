package city;

import it.unisa.dia.gas.jpbc.Element;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import rabe.*;

/**
 * Hohenberger et al., Construction 6.1: ctr, D1, D2, per-level mpk and MSB routing. Bilateral
 * sender proof / deregistration are explicitly separate extensions.
 */
public final class Compiler6 {
  public static int level(int ciphertextCounter, int keyCounter) {
    if (keyCounter < 0 || ciphertextCounter <= keyCounter)
      throw new IllegalArgumentException("NOT_REGISTERED_AT_ENCRYPTION");
    return 31 - Integer.numberOfLeadingZeros(ciphertextCounter ^ keyCounter);
  }

  public static List<Scheme> contexts(Map<String, Object> dto, Path params) {
    List<Scheme> out = new ArrayList<>();
    for (Object o : Json.list(dto.get("levels"))) out.add(Wire.context(Json.map(o), params, null));
    return out;
  }

  public static Map<String, Object> forward(Scheme.Ciphertext c) {
    return Json.obj(
        "sender",
        c.sender,
        "policy",
        Wire.policy(c.policy),
        "C1",
        Wire.enc(c.C1),
        "C2",
        Wire.enc(c.C2),
        "C3",
        Wire.encs(c.C3),
        "C4",
        Wire.enc(c.C4));
  }

  public static Scheme.Ciphertext forward(Scheme s, Map<String, Object> m) {
    Scheme.Ciphertext c = new Scheme.Ciphertext();
    c.sender = Json.num(m, "sender");
    if (c.sender < 0 || c.sender >= s.n) throw new IllegalArgumentException("sender slot");
    c.policy = Wire.policy(Json.map(m.get("policy")), s.u);
    c.C1 = Wire.dec(s.pairing, m.get("C1"), true);
    c.C2 = Wire.dec(s.pairing, m.get("C2"), false);
    c.C3 = Wire.decs(s.pairing, m.get("C3"), c.policy.rho.length);
    c.C4 = Wire.dec(s.pairing, m.get("C4"), false);
    return c;
  }

  public static Map<String, Object> proof(Scheme.Ciphertext c) {
    Map<String, Object> x = new TreeMap<>();
    for (Map.Entry<Integer, Element> e : c.X4.entrySet())
      x.put(e.getKey().toString(), e.getValue().isOne() ? null : Wire.enc(e.getValue()));
    return Json.obj(
        "sender",
        c.sender,
        "claimed",
        new ArrayList<>(c.claimed),
        "X1",
        Wire.enc(c.X1),
        "X2",
        Wire.enc(c.X2),
        "X3",
        Wire.enc(c.X3),
        "X4",
        x,
        "X5",
        c.X5.isOne() ? null : Wire.enc(c.X5));
  }

  public static Scheme.Ciphertext proof(Scheme s, Map<String, Object> m) {
    Scheme.Ciphertext c = new Scheme.Ciphertext();
    c.sender = Json.num(m, "sender");
    c.claimed = Wire.ints(m.get("claimed"), s.u);
    c.X1 = Wire.dec(s.pairing, m.get("X1"), false);
    c.X2 = Wire.dec(s.pairing, m.get("X2"), false);
    c.X3 = Wire.dec(s.pairing, m.get("X3"), false);
    c.X5 = Wire.decG1WithIdentity(s.pairing, m.get("X5"));
    c.X4 = new HashMap<>();
    for (int a : c.claimed)
      c.X4.put(a, Wire.decG1WithIdentity(s.pairing, Json.map(m.get("X4")).get("" + a)));
    return c;
  }
  /** Canonical immutable envelope fields. C4 is deliberately updatable by authorized revocation. */
  public static BigInteger challenge(Map<String, Object> envelope, BigInteger p) {
    try {
      List<Object> parts = new ArrayList<>();
      for (Object item : Json.list(envelope.get("parts"))) {
        if (item == null) {
          parts.add(null);
          continue;
        }
        Map<String, Object> part = Json.map(item), ct = Json.map(part.get("cipher"));
        parts.add(
            Json.obj(
                "block",
                part.get("block"),
                "C1",
                ct.get("C1"),
                "C2",
                ct.get("C2"),
                "C3",
                ct.get("C3"),
                "policy",
                ct.get("policy")));
      }
      Map<String, Object> canonical =
          Json.obj(
              "ctr",
              envelope.get("ctr"),
              "sender",
              envelope.get("sender"),
              "expression",
              envelope.get("expression"),
              "parts",
              parts,
              "authBlock",
              envelope.get("authBlock"),
              "nonce",
              envelope.get("nonce"),
              "payload",
              envelope.get("payload"));
      return new BigInteger(
              1,
              MessageDigest.getInstance("SHA-256")
                  .digest(Json.write(canonical).getBytes(StandardCharsets.UTF_8)))
          .mod(p);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid envelope", e);
    }
  }

  public static Map<String, Object> copy(Map<String, Object> m) {
    return Json.map(Json.read(Json.write(m)));
  }

  public static Map<String, Object> vehicle(Map<String, Object> view, int id) {
    List<Object> vs = Json.list(view.get("vehicles"));
    if (id < 0 || id >= vs.size()) throw new IllegalArgumentException("Unknown vehicle");
    return Json.map(vs.get(id));
  }

  public static void active(Map<String, Object> v) {
    if (!Boolean.TRUE.equals(v.get("registered")))
      throw new SecurityException("VEHICLE_DEREGISTERED");
  }
}
