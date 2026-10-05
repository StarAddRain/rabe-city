package city;

import it.unisa.dia.gas.jpbc.Element;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import rabe.*;

/** Isolated algebra tests: bypass HTTP eligibility checks to verify real Deregister effects. */
public final class CryptoChecks {
  static int checks = 0;

  static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    for (int ctr = 1; ctr <= 128; ctr++)
      for (int key = 0; key < ctr; key++) {
        int k = Compiler6.level(ctr, key), size = 1 << k, start = (ctr / size - 1) * size;
        check(key >= start && key < start + size, "MSB routing ctr=" + ctr + " key=" + key);
      }
    try {
      Compiler6.level(4, 4);
      throw new AssertionError("new key accepted");
    } catch (IllegalArgumentException good) {
      checks++;
    }
    Scheme s = new Scheme("parameters.properties", 4, 5);
    Scheme.Keys[] keys = new Scheme.Keys[4];
    List<Set<Integer>> attrs = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      keys[i] = s.keyGen(i);
      attrs.add(new LinkedHashSet<>(Arrays.asList(0, 1, 2, 3, 4)));
    }
    Scheme.Registry old = s.register(keys, attrs), next = s.deregister(old, 1);
    Policy policy = Policies.parse("A1 AND (A2 OR A3)", 5);
    Element m = s.message();
    Scheme.Ciphertext ct = s.encryptForward(next, policy, m);
    Element[] ri = s.initialRI();
    for (int i = 0; i < 4; i++) {
      Element recovered = s.decrypt(keys[i].r, s.transformForward(next, i, ri[i], ct));
      check(recovered.isEqual(m) == (i != 1), "Deregister algebra identity " + i);
    }
    Scheme.Ciphertext history = s.encryptForward(old, policy, m);
    check(
        s.decrypt(keys[1].r, s.transformForward(old, 1, ri[1], history)).isEqual(m),
        "old ciphertext/helper boundary");
    check(s.isValid(0, keys[0]), "valid key");
    Scheme.Keys bad =
        new Scheme.Keys(
            null,
            null,
            null,
            keys[0].T,
            keys[0].Q,
            keys[0].Z,
            keys[0].V.clone(),
            keys[0].R.clone());
    bad.V[1] = keys[0].Q;
    check(!s.isValid(0, bad), "cross-term tamper rejected");
    // Independent CRS at the single-slot level needs explicit identity DTOs.
    Scheme one = new Scheme("parameters.properties", 1, 5);
    Scheme.Keys key = one.keyGen(0);
    Scheme.Registry reg = one.register(new Scheme.Keys[] {key}, Arrays.asList(attrs.get(0)));
    reg = Wire.registry(one, Wire.registry(reg));
    BigInteger challenge = BigInteger.valueOf(13);
    Scheme.Ciphertext proof =
        Compiler6.proof(
            one, Compiler6.proof(one.senderProof(reg, 0, attrs.get(0), key.q, challenge)));
    check(one.verifyProof(reg, proof, policy, challenge), "single-slot identity round trip");
    System.out.println("CRYPTO_CHECKS_PASSED=" + checks);
  }
}
