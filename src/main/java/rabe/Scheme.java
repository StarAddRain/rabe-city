package rabe;

import it.unisa.dia.gas.jpbc.*;
import it.unisa.dia.gas.plaf.jpbc.pairing.PairingFactory;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.util.*;

/**
 * Research implementation of the supplied Construction; group elements are immutable. Slot indices
 * in Java are zero based; the mathematical slot identity is i+1. No access-control blacklist is
 * used to make cryptographic tests pass.
 */
public final class Scheme {
  public final Pairing pairing;
  public final BigInteger p;
  public final int n, u;
  public final Element g, Z, h, v, O, Y;
  public final Element[] A, B, K, E;
  public final Element[][] U;
  private final byte[][][][] W;
  private final SecureRandom random = new SecureRandom();

  public Scheme(String parameters, int slots, int universe) {
    this(parameters, slots, universe, message -> {});
  }

  public Scheme(String parameters, int slots, int universe, java.util.function.Consumer<String> progress) {
    pairing = PairingFactory.getPairing(parameters);
    if (!pairing.isSymmetric()) throw new IllegalArgumentException("symmetric pairing required");
    p = pairing.getZr().getOrder();
    n = slots;
    u = universe;
    g = pairing.getG1().newRandomElement().getImmutable();
    BigInteger alpha = scalar(), beta = scalar(), gamma = scalar(), theta = scalar();
    Z = pow(pair(g, g), alpha);
    h = pow(g, beta);
    v = pow(g, theta);
    O = pow(g, gamma);
    Y = pow(v, gamma);
    A = new Element[n];
    B = new Element[n];
    K = new Element[n];
    E = new Element[n];
    U = new Element[n][u];
    progress.accept("生成基础参数");
    BigInteger[][] us = new BigInteger[n][u];
    BigInteger[] ts = new BigInteger[n];
    ElementPowPreProcessing gp = g.getElementPowPreProcessing();
    for (int i = 0; i < n; i++) {
      ts[i] = scalar();
      A[i] = gp.pow(ts[i]).getImmutable();
      B[i] = mul(gp.pow(alpha), pow(A[i], beta));
      K[i] = gp.pow(ts[i].negate().multiply(gamma.modInverse(p)).mod(p)).getImmutable();
      E[i] = pow(mul(gp.pow(BigInteger.valueOf(i + 1)), v), ts[i]);
      for (int a = 0; a < u; a++) {
        us[i][a] = scalar();
        U[i][a] = gp.pow(us[i][a]).getImmutable();
      }
    }
    int workers = SetupCrossTerms.workers(n);
    progress.accept("准备交叉项预计算表（" + workers + " 个计算线程）");
    W = SetupCrossTerms.generate(g, p, ts, us, workers, completed -> {
      String status = "交叉项 " + completed + "/" + n + "（" + (completed * 100 / n) + "%）";
      progress.accept(status);
      if (completed % 10 == 0 || completed == n) System.out.println("Setup W slots " + completed + "/" + n);
    });
    // Setup exponents are not retained by the CRS object.
  }
  /** Rehydrate public CRS and optionally the curator-only W backing table. No secret exponents. */
  public Scheme(
      String parameters,
      int slots,
      int universe,
      Element[] globals,
      Element[][] slotsData,
      Element[][] attributes,
      byte[][][][] cross) {
    pairing = PairingFactory.getPairing(parameters);
    p = pairing.getZr().getOrder();
    n = slots;
    u = universe;
    g = globals[0];
    Z = globals[1];
    h = globals[2];
    v = globals[3];
    O = globals[4];
    Y = globals[5];
    A = slotsData[0];
    B = slotsData[1];
    K = slotsData[2];
    E = slotsData[3];
    U = attributes;
    W = cross;
  }

  public byte[][][][] exportCrossTerms() {
    return W;
  }

  public BigInteger scalar() {
    BigInteger x;
    do {
      x = new BigInteger(p.bitLength(), random);
    } while (x.signum() == 0 || x.compareTo(p) >= 0);
    return x;
  }

  public Element one() {
    return pairing.getG1().newOneElement().getImmutable();
  }

  public Element message() {
    return pairing.getGT().newRandomElement().getImmutable();
  }

  public Element pair(Element a, Element b) {
    return pairing.pairing(a, b).getImmutable();
  }

  public Element pow(Element a, BigInteger x) {
    return a.duplicate().pow(x.mod(p)).getImmutable();
  }

  public Element mul(Element a, Element b) {
    return a.duplicate().mul(b).getImmutable();
  }

  public Element div(Element a, Element b) {
    return a.duplicate().div(b).getImmutable();
  }

  public static final class Keys {
    public final BigInteger r, q, z;
    public final Element T, Q, Z;
    public final Element[] V, R;

    public Keys(
        BigInteger r,
        BigInteger q,
        BigInteger z,
        Element t,
        Element qq,
        Element zz,
        Element[] vv,
        Element[] rr) {
      this.r = r;
      this.q = q;
      this.z = z;
      T = t;
      Q = qq;
      Z = zz;
      V = vv;
      R = rr;
    }
  }

  public Keys keyGen(int i) {
    BigInteger r = scalar(), q = scalar(), z = scalar();
    Element[] vs = new Element[n], rs = new Element[n];
    for (int j = 0; j < n; j++)
      if (i != j) {
        vs[j] = pow(A[j], r);
        rs[j] = pow(A[j], q);
      }
    return new Keys(r, q, z, pow(g, r), pow(g, q), pow(g, z), vs, rs);
  }

  public static final class Registry {
    public Element T, Q;
    public final Element[] V, R, Uhat, Zkeys, Tkeys;
    public final Element[][] What;
    public final List<Set<Integer>> attributes;
    public final double[] registrationMillis;

    public Registry(int n, int u, List<Set<Integer>> sets) {
      registrationMillis = new double[n];
      V = new Element[n];
      R = new Element[n];
      Uhat = new Element[u];
      What = new Element[n][u];
      Zkeys = new Element[n];
      Tkeys = new Element[n];
      attributes = new ArrayList<>();
      for (Set<Integer> s : sets) attributes.add(Collections.unmodifiableSet(new HashSet<>(s)));
    }

    public Registry copy() {
      Registry r = new Registry(V.length, Uhat.length, attributes);
      r.T = T;
      r.Q = Q;
      System.arraycopy(V, 0, r.V, 0, V.length);
      System.arraycopy(R, 0, r.R, 0, R.length);
      System.arraycopy(Uhat, 0, r.Uhat, 0, Uhat.length);
      System.arraycopy(Zkeys, 0, r.Zkeys, 0, Zkeys.length);
      System.arraycopy(Tkeys, 0, r.Tkeys, 0, Tkeys.length);
      for (int i = 0; i < V.length; i++) System.arraycopy(What[i], 0, r.What[i], 0, Uhat.length);
      return r;
    }
  }

  public boolean isValid(int slot, Keys key) {
    if (slot < 0
        || slot >= n
        || key.T == null
        || key.Q == null
        || key.Z == null
        || key.T.isOne()
        || key.Q.isOne()
        || key.Z.isOne()) return false;
    for (int j = 0; j < n; j++)
      if (j != slot) {
        if (key.V[j] == null
            || key.R[j] == null
            || !pair(g, key.V[j]).isEqual(pair(key.T, A[j]))
            || !pair(g, key.R[j]).isEqual(pair(key.Q, A[j]))) return false;
      }
    return true;
  }
  /**
   * Section 6 Encrypt component. Sender proof is carried separately because the sender need not
   * belong to the receiving level's most recently completed block.
   */
  public Ciphertext encryptForward(Registry r, Policy policy, Element m) {
    Ciphertext c = new Ciphertext();
    c.policy = policy;
    BigInteger secret = scalar(), x = scalar();
    BigInteger[] vector = new BigInteger[policy.matrix[0].length];
    vector[0] = secret;
    for (int i = 1; i < vector.length; i++) vector[i] = scalar();
    BigInteger[] shares = policy.shares(vector, p);
    Element hx = pow(g, x), hy = div(h, hx);
    c.C1 = mul(m, pow(Z, secret));
    c.C2 = pow(g, secret);
    c.C3 = new Element[shares.length];
    for (int i = 0; i < shares.length; i++)
      c.C3[i] = mul(pow(hx, shares[i]), pow(r.Uhat[policy.rho[i]], secret.negate()));
    c.C4 = mul(pow(hy, secret), pow(r.T, secret.negate()));
    return c;
  }

  public Transformed transformForward(Registry r, int receiver, Element ri, Ciphertext c) {
    BigInteger[] w = c.policy.reconstruct(r.attributes.get(receiver), p);
    if (w == null) return null;
    Element cp = one(), wp = one();
    for (int k = 0; k < w.length; k++)
      if (w[k].signum() != 0) {
        cp = mul(cp, pow(c.C3[k], w[k]));
        wp = mul(wp, pow(r.What[receiver][c.policy.rho[k]], w[k]));
      }
    Element d1 = pair(c.C2, A[receiver]),
        d2 = mul(pair(c.C4, A[receiver]), pair(c.C2, mul(r.V[receiver], ri)));
    Element d3 = mul(pair(cp, A[receiver]), pair(c.C2, wp));
    return new Transformed(d1, mul(mul(div(c.C1, pair(c.C2, B[receiver])), d2), d3));
  }

  public Ciphertext senderProof(
      Registry r, int sender, Set<Integer> claimed, BigInteger q, BigInteger challenge) {
    Ciphertext c = new Ciphertext();
    c.sender = sender;
    c.claimed = new LinkedHashSet<>(claimed);
    BigInteger tau = scalar();
    c.X1 = mul(B[sender], pow(A[sender], challenge.multiply(tau).subtract(q)));
    c.X2 = A[sender];
    c.X3 = pow(g, tau);
    c.X5 = r.R[sender];
    c.X4 = new HashMap<>();
    for (int a : claimed) c.X4.put(a, r.What[sender][a]);
    return c;
  }

  public boolean verifyProof(Registry r, Ciphertext c, Policy reverse, BigInteger challenge) {
    BigInteger[] w = reverse.reconstruct(c.claimed, p);
    return w != null && verifySenderChallenge(r, c, reverse, w, challenge);
  }

  public Registry register(Keys[] keys, List<Set<Integer>> sets) {
    if (keys.length != n || sets.size() != n)
      throw new IllegalArgumentException("registration size");
    Registry r = new Registry(n, u, sets);
    r.T = one();
    r.Q = one();
    for (int i = 0; i < n; i++) {
      long registrationStart = System.nanoTime();
      PairingPreProcessing tp = pairing.getPairingPreProcessingFromElement(keys[i].T),
          qp = pairing.getPairingPreProcessingFromElement(keys[i].Q);
      for (int j = 0; j < n; j++)
        if (i != j) {
          if (!pair(g, keys[i].V[j]).isEqual(tp.pairing(A[j]))
              || !pair(g, keys[i].R[j]).isEqual(qp.pairing(A[j])))
            throw new SecurityException("bad cross term");
        }
      r.T = mul(r.T, keys[i].T);
      r.Q = mul(r.Q, keys[i].Q);
      r.Zkeys[i] = keys[i].Z;
      r.Tkeys[i] = keys[i].T;
      r.registrationMillis[i] = (System.nanoTime() - registrationStart) / 1e6;
      if ((i + 1) % 10 == 0) System.out.println("Reg verify " + (i + 1) + "/" + n);
    }
    for (int a = 0; a < u; a++) {
      r.Uhat[a] = one();
      for (int j = 0; j < n; j++) if (!sets.get(j).contains(a)) r.Uhat[a] = mul(r.Uhat[a], U[j][a]);
    }
    for (int i = 0; i < n; i++) {
      long aggregationStart = System.nanoTime();
      r.V[i] = one();
      r.R[i] = one();
      for (int j = 0; j < n; j++)
        if (i != j) {
          r.V[i] = mul(r.V[i], keys[j].V[i]);
          r.R[i] = mul(r.R[i], keys[j].R[i]);
        }
      for (int a = 0; a < u; a++) {
        Element w = one();
        for (int j = 0; j < n; j++)
          if (i != j && !sets.get(j).contains(a))
            w = mul(w, pairing.getG1().newElementFromBytes(W[i][j][a]));
        r.What[i][a] = w;
      }
      r.registrationMillis[i] += (System.nanoTime() - aggregationStart) / 1e6;
    }
    return r;
  }

  public static final class Ciphertext {
    public int sender;
    public Policy policy;
    public Set<Integer> claimed;
    public Element C1, C2, C4, X1, X2, X3, X5;
    public Element[] C3;
    public Map<Integer, Element> X4;

    public Ciphertext copy() {
      Ciphertext c = new Ciphertext();
      c.sender = sender;
      c.policy = policy;
      c.claimed = claimed;
      c.C1 = C1;
      c.C2 = C2;
      c.C4 = C4;
      c.X1 = X1;
      c.X2 = X2;
      c.X3 = X3;
      c.X5 = X5;
      c.C3 = C3.clone();
      c.X4 = X4 == null ? new HashMap<>() : new HashMap<>(X4);
      return c;
    }
  }

  public BigInteger hash(Ciphertext c) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      List<Element> es = new ArrayList<>();
      es.add(c.C1);
      es.add(c.C2);
      Collections.addAll(es, c.C3);
      for (Element e : es) {
        byte[] b = e.toBytes();
        out.writeInt(b.length);
        out.write(b);
      }
      return new BigInteger(1, md.digest(bytes.toByteArray())).mod(p);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Ciphertext signcrypt(
      Registry r, int sender, Policy policy, Element m, Set<Integer> claimed, BigInteger q) {
    Ciphertext c = new Ciphertext();
    c.sender = sender;
    c.policy = policy;
    c.claimed = Collections.unmodifiableSet(new HashSet<>(claimed));
    BigInteger s = scalar(), x = scalar();
    BigInteger[] vec = new BigInteger[policy.matrix[0].length];
    vec[0] = s;
    for (int j = 1; j < vec.length; j++) vec[j] = scalar();
    BigInteger[] shares = policy.shares(vec, p);
    Element hx = pow(g, x), hy = div(h, hx);
    c.C1 = mul(m, pow(Z, s));
    c.C2 = pow(g, s);
    c.C3 = new Element[shares.length];
    for (int k = 0; k < shares.length; k++)
      c.C3[k] = mul(pow(hx, shares[k]), pow(r.Uhat[policy.rho[k]], s.negate()));
    c.C4 = mul(pow(hy, s), pow(r.T, s.negate()));
    BigInteger tau = scalar();
    c.X1 = mul(B[sender], pow(A[sender], hash(c).multiply(tau).subtract(q)));
    c.X2 = A[sender];
    c.X3 = pow(g, tau);
    c.X5 = r.R[sender];
    c.X4 = new HashMap<>();
    for (int a : claimed) c.X4.put(a, r.What[sender][a]);
    return c;
  }

  public static final class Transformed {
    public final Element D1, D4;

    public Transformed(Element a, Element b) {
      D1 = a;
      D4 = b;
    }
  }
  /**
   * Bilinearity collects weighted products before pairing; algebraically identical to row-wise
   * pairings.
   */
  public Transformed transform(Registry r, int receiver, Element ri, Ciphertext c, Policy reverse) {
    BigInteger[] w = c.policy.reconstruct(r.attributes.get(receiver), p);
    if (w == null) return null;
    BigInteger[] wr = reverse.reconstruct(c.claimed, p);
    if (wr == null) return null;
    if (!verifySender(r, c, reverse, wr)) return null;
    Element cp = one(), wp = one();
    for (int k = 0; k < w.length; k++)
      if (w[k].signum() != 0) {
        cp = mul(cp, pow(c.C3[k], w[k]));
        wp = mul(wp, pow(r.What[receiver][c.policy.rho[k]], w[k]));
      }
    Element d1 = pair(c.C2, A[receiver]);
    Element d2 = mul(pair(c.C4, A[receiver]), pair(c.C2, mul(r.V[receiver], ri)));
    Element d3 = mul(pair(cp, A[receiver]), pair(c.C2, wp));
    Element d4 = mul(mul(div(c.C1, pair(c.C2, B[receiver])), d2), d3);
    return new Transformed(d1, d4);
  }

  private boolean verifySender(Registry r, Ciphertext c, Policy reverse, BigInteger[] w) {
    return verifySenderChallenge(r, c, reverse, w, hash(c));
  }

  private boolean verifySenderChallenge(
      Registry r, Ciphertext c, Policy reverse, BigInteger[] w, BigInteger challenge) {
    BigInteger split = scalar();
    Element h3 = pow(g, split), h4 = div(h, h3);
    BigInteger[] y = new BigInteger[reverse.matrix[0].length];
    y[0] = BigInteger.ONE;
    for (int j = 1; j < y.length; j++) y[j] = scalar();
    BigInteger[] shares = reverse.shares(y, p);
    Element vp = one(), xp = one();
    for (int k = 0; k < w.length; k++)
      if (w[k].signum() != 0) {
        int a = reverse.rho[k];
        Element x = c.X4.get(a);
        if (x == null) return false;
        Element vk = div(pow(h4, shares[k]), r.Uhat[a]);
        vp = mul(vp, pow(vk, w[k]));
        xp = mul(xp, pow(x, w[k]));
      }
    Element slot =
        mul(mul(pair(div(h3, r.Q), c.X2), pair(pow(c.X2, challenge), c.X3)), pair(g, c.X5));
    // The Type-A JPBC pairing implementation cannot evaluate a pairing
    // whose input is the G1 identity (it attempts to invert zero).  An
    // attribute may legitimately contribute the identity, for example
    // when a one-attribute reverse policy selects an empty What term.
    // Mathematically e(1, X) = e(X, 1) = 1, so skip those factors.
    Element attr = pairing.getGT().newOneElement().getImmutable();
    if (!vp.isOne()) attr = mul(attr, pair(vp, c.X2));
    if (!xp.isOne()) attr = mul(attr, pair(g, xp));
    return Z.isEqual(div(pair(c.X1, g), mul(slot, attr)));
  }

  public Element decrypt(BigInteger secret, Transformed c) {
    return c == null ? null : mul(pow(c.D1, secret), c.D4);
  }

  public static final class UpdateKey {
    public int owner, target;
    public Element uk, delta, phi;
    public Element[] pi;
  }

  public UpdateKey updateKeyGen(Ciphertext c, int target, Keys owner) {
    BigInteger rs = scalar(), zr = owner.z.multiply(rs).mod(p);
    UpdateKey k = new UpdateKey();
    k.owner = c.sender;
    k.target = target;
    k.delta = pow(g, rs);
    k.phi = pow(owner.Z, rs);
    k.uk = pow(c.C2, zr.negate());
    k.pi = new Element[n];
    for (int j = 0; j < n; j++) k.pi[j] = j == target ? one() : pow(A[j], zr);
    return k;
  }

  public Ciphertext updateVCS(Registry r, Ciphertext c, UpdateKey k) {
    if (k.owner != c.sender
        || !pair(k.phi, g).isEqual(pair(r.Zkeys[c.sender], k.delta))
        || !pair(k.uk, g).isEqual(pow(pair(c.C2, k.phi), BigInteger.ONE.negate())))
      throw new SecurityException("invalid VCS update");
    Ciphertext out = c.copy();
    out.C4 = mul(c.C4, k.uk);
    return out;
  }

  public Element[] updateKC(Registry r, Element[] old, UpdateKey k) {
    if (!k.pi[k.target].isOne() || !pair(k.phi, g).isEqual(pair(r.Zkeys[k.owner], k.delta)))
      throw new SecurityException("invalid KC authorization");
    PairingPreProcessing pp = pairing.getPairingPreProcessingFromElement(k.phi);
    for (int j = 0; j < n; j++)
      if (j != k.target && !pair(k.pi[j], g).isEqual(pp.pairing(A[j])))
        throw new SecurityException("invalid PI");
    Element[] out = new Element[n];
    for (int j = 0; j < n; j++) out[j] = mul(old[j], k.pi[j]);
    return out;
  }

  public Element[] initialRI() {
    Element[] out = new Element[n];
    Arrays.fill(out, one());
    return out;
  }

  public int trace(Registry r, int slot, BigInteger leaked, Element k, Element e) {
    if (slot < 0 || slot >= n || leaked == null || leaked.signum() <= 0 || leaked.compareTo(p) >= 0)
      return -1;
    if (!pairing.getG1().equals(k.getField()) || !pairing.getG1().equals(e.getField())) return -1;
    if (!pair(r.T, A[slot]).isEqual(pair(g, mul(pow(A[slot], leaked), r.V[slot])))) return -1;
    if (!mul(pair(O, k), pair(A[slot], g)).isOne()) return -1;
    if (!pair(mul(pow(O, BigInteger.valueOf(slot + 1)), Y), A[slot]).isEqual(pair(e, O))) return -1;
    return slot;
  }
  /** Locate an unknown scalar leak by its public key, then apply the paper's Trace checks. */
  public int traceLeakedScalar(Registry r, BigInteger leaked) {
    if (leaked == null || leaked.signum() <= 0 || leaked.compareTo(p) >= 0) return -1;
    Element publicKey = pow(g, leaked);
    for (int i = 0; i < n; i++)
      if (publicKey.isEqual(r.Tkeys[i])) return trace(r, i, leaked, K[i], E[i]);
    return -1;
  }

  public Registry deregister(Registry old, int slot) {
    Registry r = old.copy();
    BigInteger dr = scalar(), dq = scalar();
    r.T = mul(r.T, pow(g, dr));
    r.Q = mul(r.Q, pow(g, dq));
    for (int j = 0; j < n; j++)
      if (j != slot) {
        r.V[j] = mul(r.V[j], pow(A[j], dr));
        r.R[j] = mul(r.R[j], pow(A[j], dq));
      }
    return r;
  }
}
